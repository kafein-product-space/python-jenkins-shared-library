def call(Map config) {
    // Check object existence
    if (!config.b_config.containerConfig) {
        currentBuild.result = "ABORTED"
        error("You have to set 'containerConfig' in your yaml file.")
    }

    // Locals
    def containerImages = []  // To store image information
    def buildTasks = [:]       // For sequential build tasks
    def pushScanTasks = [:]    // For parallel push and scan tasks
    def container_repository = "${config.container_artifact_repo_address}"

    if (config.container_repo != "") {
        container_repository = "${config.container_artifact_repo_address}/${config.container_repo}"
    }

    buildDescription("Container ID: ${config.b_config.imageTag}")

    config.b_config.containerConfig.each { it ->
        def repoName = it.name.replace("_", "-").toLowerCase()
        def dockerFilePath = it.dockerFilePath.replace("_", "-")

        if (it.containsKey('copyToContext')) {
            it.copyToContext.each { ti ->
                def from = ti.from.replace("{commit-id}", config.b_config.imageTag)
                def to = ti.to.replace("{context-path}", it.contextPath)

                sh """
                cp -a ${from} ${to}
                """
            }
        }

        def imageTag = "${container_repository}/${repoName}:${config.b_config.imageTag}"
        def imageLatestTag = "${container_repository}/${repoName}:${config.b_config.imageLatestTag}"
        
        containerImages.add([imageTag: imageTag, imageLatestTag: imageLatestTag])  // Store both tags

        // Define the build task for the current image
        buildTasks["${repoName}_build"] = {
            timeout(time: 25, unit: "MINUTES") {
                stage("Building ${repoName}") {
                    script {
                        try {
                            sh """
                            docker build \
                                -t ${imageTag} \
                                -t ${imageLatestTag} \
                                -f ${dockerFilePath} \
                                ${it.contextPath}
                            """
                        } catch (Exception e) {
                            state = sh(
                                script: """
                                docker image inspect ${imageTag} 2> /dev/null && echo success || echo failed
                                """,
                                returnStdout: true
                            ).trim()

                            if (state == "failed") {
                                currentBuild.result = "ABORTED"
                                error("Error occurred when building container image. Image Name: ${it.name}")
                            }
                        }
                    }
                }
            }
        }

        // Define the push task for the current image
        pushScanTasks["${repoName}_push"] = {
            stage("Pushing ${repoName}") {
                withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: "container-artifact-registry", usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                    sh """
                    docker login --username $USERNAME --password $PASSWORD ${container_repository}
                    docker push ${imageTag} && \
                    docker push ${imageLatestTag}
                    """
                }
            }
        }

        // Define the scan task for the current image
        pushScanTasks["${repoName}_scan"] = {
            stage("Scanning ${repoName} with Trivy") {
                script {
                    lib_containerscan.trivyScan(config, imageTag)  // Call trivyscan with appropriate arguments
                }
            }
        }
    }

    // Run build tasks sequentially
    parallel buildTasks

    // Run push and scan tasks in parallel after build tasks complete
    parallel pushScanTasks

    // Run image removal sequentially after parallel tasks complete
    stage("Removing Docker Images") {
        script {
            containerImages.each { image ->
                sh """
                docker rmi ${image.imageLatestTag} || true
                docker rmi ${image.imageTag} || true
                """
            }
        }
    }

    // Assign the container images to the config object for further use
    config.containerImages = containerImages
}
