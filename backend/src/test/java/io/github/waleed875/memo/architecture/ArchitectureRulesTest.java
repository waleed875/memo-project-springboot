package io.github.waleed875.memo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable architectural boundaries. Any violation fails Maven/GitHub CI.
 * Framework-specific adapters belong in api/infrastructure/platform packages.
 */
@AnalyzeClasses(
    packages = "io.github.waleed875.memo",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {
    @ArchTest
    static final ArchRule domain_and_shared_kernel_remain_framework_free =
        noClasses().that().resideInAnyPackage("..modules..domain..", "..sharedkernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "javax.persistence..",
                "org.postgresql..",
                "..api..",
                "..infrastructure..",
                "..platform.."
            );

    @ArchTest
    static final ArchRule application_must_not_call_http_or_persistence_adapters =
        noClasses().that().resideInAPackage("..modules..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule business_modules_must_be_acyclic =
        slices().matching("..modules.(*)..")
            .should().beFreeOfCycles();
}
