pipeline {
    agent { label 'linux' }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
        // The three suites keep one hour between them, through the limits on their stages. The limit here
        // is for a PIT run, which takes longer.
        timeout(time: 240, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }

    parameters {
        choice(name: 'MUTATION_TESTING', choices: ['off', 'dry-run', 'run'],
               description: 'off runs the three suites. dry-run and run start PIT mutation testing instead of them: dry-run collects coverage and generates the mutants without running tests against them; run does the whole analysis.')
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

        stage('PIT mutation testing') {
            // Instead of the suites, not beside them. PIT changes the compiled classes in memory, in its
            // own JVMs: no source file and no class file in this workspace is changed. The POM's profile
            // mutation-testing configures it.
            when { expression { (params.MUTATION_TESTING ?: 'off') != 'off' } }
            options { timeout(time: 230, unit: 'MINUTES') }
            steps {
                sh '''#!/usr/bin/env bash
set -uo pipefail
dry_run=()
if [[ "${MUTATION_TESTING:-off}" == dry-run ]]; then
    dry_run=(-Dpit.dryRun=true)
fi
mkdir -p logs
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" -Pmutation-testing "${dry_run[@]}" \
    clean test-compile org.pitest:pitest-maven:mutationCoverage 2>&1 \
    | tee "logs/qraft-pit-$(date -u +%Y-%m-%d_%H-%M-%S)-$BUILD_NUMBER.log"
exit "${PIPESTATUS[0]}"
'''
            }
            post {
                always {
                    sh '''#!/usr/bin/env bash
set -euo pipefail
if [[ -d target/pit-reports ]]; then
    mkdir -p reports/pit
    cp -a target/pit-reports/. reports/pit/
fi
'''
                }
            }
        }

        stage('Default tests and coverage gates') {
            when { expression { (params.MUTATION_TESTING ?: 'off') == 'off' } }
            options { timeout(time: 25, unit: 'MINUTES') }
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
            when { expression { (params.MUTATION_TESTING ?: 'off') == 'off' } }
            options { timeout(time: 10, unit: 'MINUTES') }
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
            when { expression { (params.MUTATION_TESTING ?: 'off') == 'off' } }
            options { timeout(time: 25, unit: 'MINUTES') }
            steps {
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
