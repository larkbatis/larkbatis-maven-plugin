/**
 * Maven plugin for LightBatis: a build extension wires mapper XML into the
 * generator core ({@code lightbatis-processor}) by injecting the
 * {@code -Alightbatis.mapperDir} compiler argument and the processor path
 * into {@code maven-compiler-plugin} before the execution plan is calculated.
 * See {@link io.github.lightbatis.maven.LightBatisLifecycleParticipant}.
 */
package io.github.lightbatis.maven;
