# Open Source Usage and License Compliance Guide

## Overview

Qraft is an open source project licensed under the **Apache License 2.0**. This document outlines the open source components used, license requirements, and compliance guidelines.

## Project License

**License:** Apache License 2.0  
**Copyright:** 2025 Mark Andrew Ray-Smith Cityline Ltd  
**License File:** [LICENSE](./LICENSE)  
**Attribution File:** [NOTICE](./NOTICE)

### Apache License 2.0 Summary

**Permissions:**
- Commercial use
- Modification
- Distribution
- Patent use
- Private use

**Conditions:**
- License and copyright notice
- State changes
- Include NOTICE file

**Limitations:**
- Trademark use
- Liability
- Warranty

## Required License Headers

All Java source files must include the following license header:

```java
/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

## Third-Party Dependencies

**Reconciled 2026-10-03.** This is the inventory of active, directly declared
external library dependencies in the reactor POMs, including inherited
dependencies. Qraft's own modules, Maven plugins, imported BOMs, commented-out
dependencies, and transitive libraries are not additional rows. The POMs are
the version authority; this table is a dated documentation snapshot.

License names below report upstream artifact POM metadata (including inherited
parent metadata), with SLF4J's terms documented by its
[upstream license](https://www.slf4j.org/license.html). They do not replace the
license and notice files distributed with each artifact or constitute a
complete license inventory of the shaded runtime.

### Production dependencies

| Maven coordinates | Version | Declared license metadata |
|---|---|---|
| `io.github.mraysmit:raftlog-core` | 1.4.1 | Apache License 2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.19.4 | Apache License 2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.19.4 | Apache License 2.0 |
| `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `io.grpc:grpc-netty` | 1.68.1 | Apache License 2.0 |
| `com.google.protobuf:protobuf-java`, `com.google.protobuf:protobuf-java-util` | 3.25.5 | BSD 3-Clause |
| `com.google.guava:guava` | 33.5.0-jre | Apache License 2.0 |
| `io.opentelemetry:opentelemetry-sdk`, `io.opentelemetry:opentelemetry-exporter-otlp` | 1.59.0 | Apache License 2.0 |
| `io.opentelemetry:opentelemetry-exporter-prometheus` | 1.59.0-alpha | Apache License 2.0 |
| `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` | 2.14.0-alpha | Apache License 2.0 |
| `org.slf4j:slf4j-api`, `org.slf4j:jul-to-slf4j` | 2.0.17 | MIT |
| `ch.qos.logback:logback-classic` | 1.5.32 | EPL 2.0 or LGPL 2.1 |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | Apache License 2.0 and MIT entries |
| `javax.annotation:javax.annotation-api` | 1.3.2 | CDDL + GPLv2 with Classpath Exception |

Logback is also inherited in test scope throughout the reactor. Production
modules declare the scope they need, including the runtime's explicit runtime
dependency so the executable JAR contains its logging backend.

### Test dependencies

| Maven coordinates | Version | Declared license metadata |
|---|---|---|
| `org.junit.jupiter:junit-jupiter` | 5.14.3 | EPL 2.0 |
| `org.awaitility:awaitility` | 4.3.0 | Apache License 2.0 |
| `org.testcontainers:testcontainers`, `org.testcontainers:testcontainers-junit-jupiter` | 2.0.3 | MIT |

### Version sources and release inventory

- [pom.xml](pom.xml) manages RaftLog, SLF4J, Logback, the JSON logging encoder,
  JUnit, and the Testcontainers version property. It imports OpenTelemetry BOM
  1.59.0 and instrumentation BOM 2.14.0-alpha, and pins the Prometheus exporter
  at 1.59.0-alpha across modules.
- Module POMs declare Jackson, gRPC, protobuf, Guava, the JUL bridge, annotations,
  and Awaitility versions. The controller explicitly declares the SDK and
  exporter versions; the instrumentation BOM supplies the Logback appender
  version.
- Before distribution, resolve the production dependency tree for
  `qraft-runtime`, compare it with the shaded JAR, and retain the licenses and
  notices for its actual transitive contents. Test libraries and build plugins
  are separate from that production inventory.
- Follow [PROJECT_STANDARDS.md](docs/PROJECT_STANDARDS.md#42-test-doubles) for
  permitted test doubles. The dependency inventory must not introduce a
  prohibited mocking library or a substitute framework.

## Compliance Requirements

### For Distribution

1. **Include License File:** Copy of Apache License 2.0
2. **Include NOTICE File:** Attribution notices for all dependencies
3. **Preserve Copyright Notices:** Keep all existing copyright headers
4. **Document Changes:** If you modify the code, document the changes

### For Commercial Use

**Allowed:**
- Use in commercial products
- Sell products containing Qraft
- Modify for commercial purposes
- Create proprietary derivatives

**Required:**
- Include license and copyright notices
- Include NOTICE file in distributions
- Don't use "Qraft" trademark without permission

### For Modification

**Allowed:**
- Modify source code
- Create derivative works
- Distribute modifications

**Required:**
- Mark modified files with change notices
- Include original license headers
- Include NOTICE file

## Attribution Requirements

When using Qraft in your project, include:

### In Documentation
```
This product includes Qraft (https://github.com/mraysmit/qraft)
Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
Licensed under the Apache License 2.0
```

### In Software
- Include the NOTICE file in your distribution
- Preserve all copyright headers in source code
- Include Apache License 2.0 text

## Automated Compliance

### Source header audit

The required header and attributed type Javadoc are defined in
[PROJECT_STANDARDS.md, section 10.1](docs/PROJECT_STANDARDS.md#101-source-file-headers).
Audit all current production and test Java sources against that contract; do
not infer completion from a historical source-file count. The previously named
`update-java-headers.ps1` is not present in this repository.

### Maven License Plugin

Consider adding the Maven License Plugin to your build:

```xml
<plugin>
    <groupId>com.mycila</groupId>
    <artifactId>license-maven-plugin</artifactId>
    <version>4.2</version>
    <configuration>
        <header>LICENSE-HEADER.txt</header>
        <includes>
            <include>**/*.java</include>
        </includes>
    </configuration>
</plugin>
```

## Frequently Asked Questions

### Q: Can I use Qraft in my commercial product?
**A:** Yes, the Apache License 2.0 explicitly allows commercial use.

### Q: Do I need to open source my modifications?
**A:** No, Apache License 2.0 does not require derivative works to be open source.

### Q: Can I remove the license headers?
**A:** No, you must preserve all copyright and license notices.

### Q: Do I need to contribute back my changes?
**A:** No, but contributions are welcome and appreciated.

### Q: Can I use the "Qraft" name for my product?
**A:** The license doesn't grant trademark rights. Contact the copyright holder for trademark usage.

## Implementation Status

The repository contains [LICENSE](LICENSE), [NOTICE](NOTICE), and the source
header contract. `NOTICE` identifies Qraft and directs readers to upstream
dependency terms; it is not a generated list of every bundled component.
The direct dependency tables above were reconciled on 2026-10-03. A complete
release inventory and source-header audit must be performed on the artifact and
sources being distributed; no blanket completion claim is made here.

## Contact

For license questions or trademark permissions:
- **Copyright Holder:** Mark Andrew Ray-Smith Cityline Ltd
- **Project Repository:** [mraysmit/qraft](https://github.com/mraysmit/qraft)

## Resources

- [Apache License 2.0 Full Text](https://www.apache.org/licenses/LICENSE-2.0)
- [Apache License FAQ](https://www.apache.org/foundation/license-faq.html)
- [Open Source Initiative](https://opensource.org/licenses/Apache-2.0)
- [SPDX License Identifier](https://spdx.org/licenses/Apache-2.0.html)

---

**Note:** This document provides general guidance. For specific legal questions, consult with a qualified attorney familiar with open source licensing.
