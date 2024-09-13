// README: This library works with Teams Workflows (PowerAutomate app, not connector. You must configure Credentials id and webhook url)
def call(String status, String message, String credentialsId) {
    def colors = [
        'Success': 'Good',         // green color for success
        'Failure': 'Attention'     // red color for failure
    ]

    // Additional color for Trivy scan status
    def trivyColors = [
        'Vulnerabilities found': 'Attention', // red color for vulnerabilities found
        'Clean': 'Good',                   // green color for no vulnerabilities
        'Trivy scan status not available': 'Default' // default color for null/empty status
    ]

    if (!colors.containsKey(status)) {
        error "Invalid status: $status"
    }

    def color = colors[status]
    def title = "Jenkins Notification"
    def jobName = env.JOB_NAME        // Get the Jenkins job name
    def jobLink = env.BUILD_URL       // Get the direct link to the Jenkins job
    def buildNumber = env.BUILD_NUMBER // Get the build number

    // Determine Trivy scan status color
    def trivyStatus = env.TRIVY_STATUS ?: 'Trivy scan status not available'
    def trivyColor = trivyColors[trivyStatus] ?: 'Default'

    withCredentials([string(credentialsId: "${credentialsId}", variable: 'webhookUrl')]) {
        def payload = [
            "type": "message",
            "attachments": [
                [
                    "contentType": "application/vnd.microsoft.card.adaptive",
                    "content": [
                        "\$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.3",
                        "body": [
                            [
                                "type": "TextBlock",
                                "size": "Medium",
                                "weight": "Bolder",
                                "text": "${title}"
                            ],
                            [
                                "type": "ColumnSet",
                                "columns": [
                                    [
                                        "type": "Column",
                                        "items": [
                                            [
                                                "type": "Image",
                                                "style": "Person",
                                                "url": "https://ftp-chi.osuosl.org/pub/jenkins/art/jenkins-logo/1024x1024/logo.png",
                                                "altText": "Jenkins",
                                                "size": "Medium"
                                            ]
                                        ],
                                        "width": "auto"
                                    ],
                                    [
                                        "type": "Column",
                                        "items": [
                                            [
                                                "type": "TextBlock",
                                                "weight": "Bolder",
                                                "text": "Jenkins Notification",
                                                "wrap": true
                                            ]
                                        ],
                                        "width": "auto"
                                    ]
                                ]
                            ],
                            [
                                "type": "TextBlock",
                                "weight": "Bolder",
                                "color": "${color}",
                                "text": "${status}",
                                "wrap": true
                            ],
                            [
                                "type": "TextBlock",
                                "text": "${message}",
                                "wrap": true
                            ],
                            [
                                "type": "TextBlock",
                                "weight": "Bolder",
                                "color": "${trivyColor}",
                                "text": "Trivy Scan Status: ${trivyStatus}",
                                "wrap": true
                            ],
                            [
                                "type": "TextBlock",
                                "weight": "Bolder",
                                "text": "Job Name: ${jobName} (Build #${buildNumber})",
                                "wrap": true
                            ],
                            [
                                "type": "TextBlock",
                                "weight": "Bolder",
                                "text": "[Click here to view the job](${jobLink})",
                                "wrap": true
                            ]
                        ]
                    ]
                ]
            ]
        ]

        // Send the notification to the Teams webhook
        def response = httpRequest(
            httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: groovy.json.JsonOutput.toJson(payload),
            url: webhookUrl
        )

        echo "Response: ${response}"
    }
}

def getBuildTime() {
    def durationMillis = currentBuild.duration ?: 0

    // Convert durationMillis to seconds
    def durationSeconds = (durationMillis / 1000).toLong()

    // Calculate minutes and seconds
    def minutes = (durationSeconds / 60).toLong()
    def seconds = (durationSeconds % 60).toLong()

    return "${minutes}m ${seconds}s"
}
