def call(Map config) {

    pipeline {
        agent {label config.agent}

        parameters {
            string(name: 'IMAGE', defaultValue: '', description: '')
            string(name: 'BRANCH', description: 'Branch to build', defaultValue: '')
        }

        stages {
            stage("Configure Init") {
                steps {
                    script {
                        lib_helper.configureInit(
                            config
                        )
                    }
                }
            }

            stage("Checkout Project Code") {
                steps {
                    checkout scm: [
                        $class: "GitSCM",
                        branches: [[name: "refs/heads/${config.target_branch}"]],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            config.scm_global_config
                        ]
                    ]
                }
            }

            stage("Read Project Config") {
                steps {
                    script {
                        // Create config file variable
                        config.config_file = config.containsKey('config_file_path') ? config.config_file_path : ".jenkins/buildspec.yaml"
                        config.b_config = readYaml file: config.config_file
                        config.job_base = sh(
                            script: "python3 -c 'print(\"/\".join(\"${JOB_NAME}\".split(\"/\")[:-1]))'",
                            returnStdout: true
                        ).trim()
                        config.job_name = sh(
                            script: "python3 -c 'print(\"${JOB_NAME}\".split(\"/\")[-1])'",
                            returnStdout: true
                        ).trim()
                        commitID = sh(
                            script: """
                            git log --pretty=format:"%h" | head -1
                            """,
                            returnStdout: true
                        ).trim()

                        // Configure image from params
                        if ( params.containsKey("IMAGE") && params.IMAGE != "" ) {
                            config.image = params.IMAGE
                        } else {
                            currentBuild.result = "ABORTED"
                            buildDescription("Error: You have to set IMAGE_ID parameter for branch deployment.")
                            error("You have to set 'IMAGE' parameter.")
                        }

                        // Set container id global
                        env.CONTAINER_IMAGE_ID = config.image

                        config.b_config.imageTag = commitID
                        config.b_config.imageLatestTag = "latest"
                        config.commitID = commitID

                        if ( config.b_config.containsKey("sequentialDeploymentMapping") ) {
                            config.sequential_deployment_mapping = config.b_config.sequentialDeploymentMapping[config.target_branch]
                        }
                    }
                }
            }

            stage("Deploy New Code") {
                when {
                    expression {
                        return config.b_config.controllers.deployController
                    }
                }
                steps {
                    script {
                        lib_deployController(
                            config
                        )
                    }
                }
            }

        }

        post {
            always {
                script {
                    lib_cleanupController(config)
                    lib_postbuildController(config)
                }
            }
            success {
                buildDescription("Container ID: ${env.CONTAINER_IMAGE_ID}")

                script {
                    lib_helper.triggerJob(
                        config
                    )
                }
                script {
                    def buildTime = lib_teamsnotifications.getBuildTime()
                    lib_teamsnotifications('Success', "The deployment has completed successfully in ${buildTime}. Current version is: ${env.CONTAINER_IMAGE_ID}", 'teams-webhook-url')
                }
                script {
                    def publisher = LastChanges.getLastChangesPublisher("PREVIOUS_REVISION", "SIDE", "LINE", true, true, "", "", "", "", "")
                    publisher.publishLastChanges()
                    def htmlDiff = publisher.getHtmlDiff()
                    writeFile file: 'build-diff.html', text: htmlDiff

                    lib_helper.triggerJob(config)
                }
            }
            failure {
                script {
                    def buildTime = lib_teamsNotifications.getBuildTime()
                    lib_teamsNotifications.notify('Failure', "The build has failed after ${buildTime}. Please check the logs for details.", 'teams-webhook-url')
                }
            }
        }

    }
}
