package io.quarkus.test.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.test.common.DevServicesContext;

/**
 * Annotation that indicates that this test should run the result of the Quarkus build.
 * If a jar was created, it is launched using {@code java -jar ...}
 * (and thus runs in a separate JVM than the test).
 * If instead a native image was created, that image is launched.
 * Finally, if a container image was created during the build, then a new container is created and run.
 * <p>
 * The standard usage pattern is expected to be a base test class that runs the
 * tests using the JVM version of Quarkus, with a subclass that extends the base
 * test and is annotated with this annotation to perform the same checks against
 * the native image.
 * <p>
 * Note that it is not possible to mix {@code @QuarkusTest} and {@code @QuarkusIntegrationTest} in the same test
 * run, it is expected that the {@code @QuarkusTest} tests will be standard unit tests that are
 * executed by surefire, while the {@code @QuarkusIntegrationTest} tests will be integration tests
 * executed by failsafe.
 * This also means that injecting beans into a test class using {@code @Inject} is not supported
 * with {@code @QuarkusIntegrationTest}. Such injections are only possible in tests annotated with
 * {@code @QuarkusTest} so the test class structure must take this into account.
 */
@Target(ElementType.TYPE)
@ExtendWith({ DisabledOnIntegrationTestCondition.class, QuarkusIntegrationTestExtension.class })
@Retention(RetentionPolicy.RUNTIME)
public @interface QuarkusIntegrationTest {

    /**
     * If used as a field of class annotated with {@link QuarkusIntegrationTest}, the field is populated
     * with an implementation that allows accessing contextual test information
     *
     * @deprecated Use {@link DevServicesContext} instead.
     */
    @Deprecated
    interface Context extends DevServicesContext {

    }

}
