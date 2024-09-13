def trivyScan(Map config, String imageName, String outputDir = "trivy-reports", String templateDir = '/home/jenkins/.templates') {
    script {
        try {
            echo "Running Trivy scan for image: ${imageName}"

            // Pull Trivy image
            sh "docker pull aquasec/trivy:latest"

            // Ensure the output directory exists in the Jenkins workspace
            sh "mkdir -p ${outputDir}"

            // Run Trivy scan and capture exit code
            def result = sh(script: """
            docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                -v ${templateDir}/.cache:/root/.cache/ \
                -v ${templateDir}/html.tpl:/html.tpl \
                -v ${env.WORKSPACE}/${outputDir}:/${outputDir} \
                aquasec/trivy:latest image \
                --no-progress --format template --scanners vuln \
                --template "@html.tpl" \
                --exit-code 1 \
                --severity MEDIUM,HIGH,CRITICAL \
                --output /${outputDir}/trivy-report-${config.b_config.project.name}.html \
                ${imageName}
            """, returnStatus: true)  // Capture exit code

            // Handle Trivy scan result
            if (result != 0) {
                echo "Vulnerabilities found in the image: ${imageName}."
                env.TRIVY_STATUS = "Vulnerabilities found"
                // DO NOT fail the build, just print the message
            } else {
                echo "No vulnerabilities found in the image: ${imageName}."
                env.TRIVY_STATUS = "Clean"
            }

            // Change ownership of the files to Jenkins user (if required)
            sh "sudo chown -R 1000:1000 ${outputDir}"

            // Archive the report as a Jenkins artifact
            archiveArtifacts artifacts: "${outputDir}/trivy-report-${config.b_config.project.name}.html", allowEmptyArchive: false

        } catch (Exception e) {
            error "Trivy scan failed: ${e.message}"
        }
    }
}
