pipeline {
    agent { label 'linux' }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }

    environment {
        JAVA_HOME = "${WORKSPACE}@tools/sapmachine-jdk-27"
        PATH = "${WORKSPACE}@tools/sapmachine-jdk-27/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                sh 'git rev-parse HEAD'
            }
        }

        stage('Toolchain and Docker') {
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    mkdir -p "$JAVA_HOME"
    archive="${JAVA_HOME}.tar.gz"
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
        --output "$archive" \
        https://github.com/SAP/SapMachine/releases/download/sapmachine-27/sapmachine-jdk-27_linux-x64_bin.tar.gz
    printf '%s  %s\\n' de400f5991c440d9a454e8d6470cec398ed5cf13277ba890d519d8c661bd8194 "$archive" | sha256sum --check -
    tar -xzf "$archive" -C "$JAVA_HOME" --strip-components=1
    rm -- "$archive"
fi
java --version
javac --version
mvn --version
docker version
docker compose version
'''
            }
        }

        stage('Default tests and coverage gates') {
            steps {
                sh '''#!/usr/bin/env bash
set -uo pipefail
mkdir -p logs
mvn -B --fail-at-end -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" clean install 2>&1 \
    | tee "logs/qraft-default-$(date -u +%Y-%m-%d_%H-%M-%S)-$BUILD_NUMBER.log"
exit "${PIPESTATUS[0]}"
'''
            }
            post {
                always {
                    sh '''#!/usr/bin/env bash
set -euo pipefail
if [[ -d target/surefire-reports ]]; then
    mkdir -p reports/default
    cp -a target/surefire-reports/. reports/default/
fi
if [[ -d target/site/jacoco ]]; then
    mkdir -p coverage
    cp -a target/site/jacoco/. coverage/
fi
'''
                }
            }
        }

        stage('End-to-end tests') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh '''#!/usr/bin/env bash
set -uo pipefail
rm -rf -- target/surefire-reports
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    -Dgroups=e2e -Dtest.excludedGroups= 2>&1 \
    | tee "logs/qraft-e2e-$(date -u +%Y-%m-%d_%H-%M-%S)-$BUILD_NUMBER.log"
exit "${PIPESTATUS[0]}"
'''
                }
            }
            post {
                always {
                    sh '''#!/usr/bin/env bash
set -euo pipefail
if [[ -d target/surefire-reports ]]; then
    mkdir -p reports/e2e
    cp -a target/surefire-reports/. reports/e2e/
fi
'''
                }
            }
        }

        stage('Docker cluster tests') {
            steps {
                // These tests create and remove Docker networks, which changes the network
                // interfaces of the host. A browser test of another job on this node then loses
                // its page load. The PeeGeeQ UI stage holds the same lock, so each waits for the
                // other. The step needs the Lockable Resources plugin.
                lock('host-network-interfaces') {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh '''#!/usr/bin/env bash
set -uo pipefail
rm -rf -- target/surefire-reports
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    '-Dgroups=docker|slow' -Dtest.excludedGroups= 2>&1 \
    | tee "logs/qraft-docker-$(date -u +%Y-%m-%d_%H-%M-%S)-$BUILD_NUMBER.log"
exit "${PIPESTATUS[0]}"
'''
                    }
                }
            }
            post {
                always {
                    sh '''#!/usr/bin/env bash
set -euo pipefail
if [[ -d target/surefire-reports ]]; then
    mkdir -p reports/docker
    cp -a target/surefire-reports/. reports/docker/
fi
'''
                }
            }
        }
    }

    post {
        always {
            junit testResults: 'reports/**/TEST-*.xml', allowEmptyResults: true
            archiveArtifacts artifacts: 'logs/**, reports/**, coverage/**, **/hs_err_pid*.log, **/replay_pid*.log',
                             allowEmptyArchive: true
        }
    }
}
