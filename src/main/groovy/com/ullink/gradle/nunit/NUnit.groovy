package com.ullink.gradle.nunit

import org.bouncycastle.math.raw.Nat
import org.gradle.api.internal.ConventionTask
import groovyx.gpars.GParsPool
import org.gradle.api.GradleException
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

import static org.apache.tools.ant.taskdefs.condition.Os.*

class NUnit extends ConventionTask {
    def nunitHome
    def nunitVersion
    def nunitDownloadUrl
    List testAssemblies

    def framework
    def verbosity
    def config
    def timeout

    def reportFolder
    boolean useX86 = false
    boolean shadowCopy = false
    String reportFileName = 'TestResult.xml'
    boolean ignoreFailures = false
    boolean parallelForks = true

    def test
    def testList

    NUnit() {
        conventionMapping.map "reportFolder", { new File(outputFolder, 'reports') }
        inputs.files {
            getTestAssemblies()
        }
    }

    boolean getIsV3() {
        getIsV3(getNunitVersion())
    }

    static boolean getIsV3(def version) {
        version.startsWith('3.')
    }

    void setNunitVersion(def version) {
        this.nunitVersion = version
        if (getIsV3(version)) {
            this.metaClass.mixin NUnit3Mixins
        }
    }

    File nunitBinFile(String file) {
        assert getNunitHome(), "You must install NUnit and set nunit.home property or NUNIT_HOME env variable"
        new File(project.file(getNunitHome()), "bin/${file}")
    }

    File getOutputFolder() {
        new File(project.buildDir, 'nunit')
    }

    File getReportFolderImpl() {
        project.file(getReportFolder())
    }

    @OutputFile
    File getTestReportPath() {
        // for the non-default nunit tasks, ensure we write the report in a separate file
        // TODO: Do we need to supply prefix when user has specified its own report file name?
        def reportFileNamePrefix = name == 'nunit' ? '' : name
        new File(getReportFolderImpl(), reportFileNamePrefix + reportFileName)
    }

    @TaskAction
    def build() {
        decideExecutionPath(this.&singleRunExecute, this.&multipleRunsExecute)
    }

    def decideExecutionPath(Closure singleRunAction, Closure multipleRunsAction) {
        if (!parallelForks || !test) {
            return singleRunAction(test)
        } else {
            return multipleRunsAction(test)
        }
    }

    def singleRunExecute(def test) {
        def testRuns = getTestInputsAsString(test)
        testRun(testRuns, getTestReportPath())
    }

    def multipleRunsExecute(def test) {
        def intermediatReportsPath = new File(getReportFolderImpl(), "intermediate-results-" + name)
        intermediatReportsPath.mkdirs()

        def testRuns = getTestInputAsList(test)
        GParsPool.withPool {
            testRuns.eachParallel { testRun(it, new File(intermediatReportsPath, it + ".xml")) }
        }

        mergeTestReports(intermediatReportsPath.listFiles(), getTestReportPath())
    }

    // Used by gradle-opencover-plugin
    def getCommandArgs() {
        def testRuns = getTestInputsAsString(this.getTest())
        buildCommandArgs (testRuns, getTestReportPath())
    }

    void mergeTestReports(File[] files, File outputFile) {
        logger.info("Merging test reports $files into $outputFile ...")
        outputFile.write(new NUnitTestResultsMerger().merge(files.collect { it.text }))
    }

    List<String> getTestInputAsList(def testInput)
    {
        if (!testInput){
            return []
        }

        if (testInput instanceof List) {
            return testInput
        }

        // Behave like NUnit
        if (testInput.contains(',')) {
            return testInput.tokenize(',')
        }

        return [testInput]
    }

    String getTestInputsAsString(def testInput)
    {
        if (!testInput){
            return ''
        }

        if (testInput instanceof String) {
            return testInput
        }

        return testInput.join(',')
    }

    def testRun(def test, def reportPath) {
        def cmdLine = [getNunitExec().absolutePath, *buildCommandArgs(test, reportPath)]
        if (!isFamily(FAMILY_WINDOWS)) {
            cmdLine = ["mono", *cmdLine]
        }
        execute(cmdLine)
    }

    // Return values of nunit v2 and v3 are defined in
    // https://github.com/nunit/nunitv2/blob/master/src/ConsoleRunner/nunit-console/ConsoleUi.cs and
    // https://github.com/nunit/nunit/blob/master/src/NUnitConsole/nunit-console/ConsoleRunner.cs
    def execute(commandLineExec) {
        prepareExecute()

        def mbr = project.exec {
            commandLine = commandLineExec
            ignoreExitValue = ignoreFailures
        }

        int exitValue = mbr.exitValue
        if (exitValue == 0) {
            return
        }

        boolean anyTestFailing = exitValue > 0
        if (anyTestFailing && ignoreFailures) {
            return
        }

        throw new GradleException("${getNunitExec()} execution failed (ret=${mbr.exitValue})");
    }

    def prepareExecute() {
        getReportFolderImpl().mkdirs()
    }

    def buildCommandArgs(def testInput, def testReportPath) {
        def commandLineArgs = []

        String verb = verbosity
        if (!verb) {
            if (logger.debugEnabled) {
                verb = 'Verbose'
            } else if (logger.infoEnabled) {
                verb = 'Info'
            } else {
                // 'quiet'
                verb = 'Warning'
            }
        }
        if (verb) {
            commandLineArgs += "-trace=$verb"
        }
        if (framework) {
            commandLineArgs += "-framework:$framework"
        }
        if (config) {
            commandLineArgs += "-config:$config"
        }
        if (timeout) {
            commandLineArgs += "-timeout:$timeout"
        }
        commandLineArgs += "-work:$outputFolder"

        commandLineArgs += buildAdditionalCommandArgs(testInput, testReportPath)

        getTestAssemblies().each {
            def file = project.file(it)
            if (file.exists())
                commandLineArgs += file
            else
                commandLineArgs += it
        }

        commandLineArgs
    }
}
