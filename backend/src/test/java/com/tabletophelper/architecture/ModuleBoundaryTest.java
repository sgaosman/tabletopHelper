package com.tabletophelper.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundaries (D151). Each package directly below com.tabletophelper is a module, and only the
 * module itself may use its internal package. rules is plain Java, so it may use only the JDK, itself
 * and shared. encounter depends on character, so character asks what it needs of encounters through
 * interfaces of its own that encounter implements, such as LiveEncounters, and uses nothing from encounter.
 * Test classes are not checked.
 */
class ModuleBoundaryTest {

    private static final PackageMatcher MODULE = PackageMatcher.of("com.tabletophelper.(*)..");
    private static final PackageMatcher INTERNAL_PACKAGE = PackageMatcher.of("com.tabletophelper.(*).internal..");

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tabletophelper");
    }

    @Test
    @DisplayName("No class outside a module uses that module's internal package")
    void internalPackagesAreUsedOnlyByTheirOwnModule() {
        classes().should(useNoOtherModulesInternalPackage()).check(productionClasses);
    }

    @Test
    @DisplayName("rules depends only on the JDK, rules itself and shared")
    void rulesDependsOnlyOnTheJdkRulesAndShared() {
        classes().that().resideInAPackage("com.tabletophelper.rules..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.tabletophelper.rules..", "com.tabletophelper.shared..")
                .check(productionClasses);
    }

    @Test
    @DisplayName("character uses nothing from encounter: the dependency runs from encounter to character only")
    void characterDoesNotDependOnEncounter() {
        noClasses().that().resideInAPackage("com.tabletophelper.character..")
                .should().dependOnClassesThat().resideInAPackage("com.tabletophelper.encounter..")
                .check(productionClasses);
    }

    @Test
    @DisplayName("No class uses Jackson 2, which stays inside jjwt: the application's JSON is Jackson 3's")
    void noClassUsesJackson2() {
        // Jackson 2 is still on the classpath for jjwt-jackson and springdoc, so an old import would compile.
        // The annotations keep their com.fasterxml package in Jackson 3 and are allowed (D154)
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.fasterxml.jackson.core..", "com.fasterxml.jackson.databind..",
                        "com.fasterxml.jackson.datatype..", "com.fasterxml.jackson.module..")
                .check(productionClasses);
    }

    private static ArchCondition<JavaClass> useNoOtherModulesInternalPackage() {
        return new ArchCondition<>("use no other module's internal package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originModule = module(MODULE, origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Optional<String> internalOf = module(INTERNAL_PACKAGE, dependency.getTargetClass());
                    if (internalOf.isPresent() && !internalOf.equals(originModule)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** The module the matcher captures from the class's package; empty when the package doesn't match. */
    private static Optional<String> module(PackageMatcher matcher, JavaClass javaClass) {
        return matcher.match(javaClass.getPackageName()).map(result -> result.getGroup(1));
    }
}
