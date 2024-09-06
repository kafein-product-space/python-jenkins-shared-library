def call(Map config) {

    if ( config.containsKey("github_hook") && config.github_hook ) {
        properties([pipelineTriggers([githubPush()])])
    }

    pipeline {
        agent {label config.agent}

        options {
            timeout(time: 25, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '15'))
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'BRANCH', description: 'Branch to build', defaultValue: '')
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'REF', value: '$.push.changes[0].old.name'],
                ],
                 causeString: 'Triggered by Bıtbucket',
                 token: 'bitbucket_' + config.sonar_qube_project_key,
                 printContributedVariables: false,
                 printPostContent: false,
                 silentResponse: false,
                 shouldNotFlattern: false,

                 regexpFilterText: '$REF',
                 regexpFilterExpression: '^(development|uat)'
            )
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
                        branches: [[name: config.target_branch.startsWith("refs") ? config.target_branch : "refs/heads/${config.target_branch}"]],
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

                        // Configure commit ID for project
                        commitID = sh(
                            script: """
                            git log --pretty=format:"%h" | head -1
                            """,
                            returnStdout: true
                        ).trim()

                        // Define variable for container build
                        config.b_config.imageTag = commitID
                        config.b_config.imageLatestTag = "latest"

                        config.commitID = commitID

                        if ( config.b_config.containsKey("sequentialDeploymentMapping") ) {
                            config.sequential_deployment_mapping = config.b_config.sequentialDeploymentMapping[config.target_branch]
                        }
                    }
                }
            }

            stage("Change Version Number") {
                when {
                    expression {
                        return config.b_config.controllers.versionNumberController
                    }
                }
                steps {
                    script {
                        lib_versionController(
                            config
                        )
                    }
                }
            }

            stage("Run Unit tests") {
                when {
                    expression {
                        return config.b_config.controllers.unitTestController &&
                            config.b_config.controllers.buildController
                    }
                }
                steps {
                    script {
                        lib_unitTestController(
                            config
                        )
                    }
                }
            }

            stage("Build and Publish as a Container") {
                when {
                    expression {
                        return config.b_config.controllers.containerController
                    }
                }
                steps {
                    script {
                        lib_containerController(
                            config
                        )
                    }
                }
            }

            stage("Security Scan For Container") {
                when {
                    expression {
                        return config.scan_container_image
                    }
                }
                steps {
                    sh """
                    echo ${config.containerImages.join("\n")} > anchore_images
                    """
                    anchore name: "anchore_images", bailOnFail: false
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
                script {
                    def buildTime = lib_teamsnotifications.getBuildTime()
                    lib_teamsnotifications('Success', "The build has completed successfully in ${buildTime}.", 'teams-webhook-url')
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
                    def buildTime = lib_teamsnotifications.getBuildTime()
                    lib_teamsnotifications('Failure', "The build has failed after ${buildTime}. Please check the logs for details.", 'teams-webhook-url')
                }
            }
        }
    }
}
