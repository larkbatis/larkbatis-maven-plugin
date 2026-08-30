/**
 * Maven plugin for LarkBatis: a build extension wires mapper XML into the
 * generator core ({@code larkbatis-processor}) by injecting the
 * {@code -Alarkbatis.mapperDir} compiler argument and the processor path
 * into {@code maven-compiler-plugin} before the execution plan is calculated.
 * See {@link io.github.larkbatis.maven.LarkBatisLifecycleParticipant}.
 */
package io.github.larkbatis.maven;
