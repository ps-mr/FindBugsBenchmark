/*
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2000-2002 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowlegement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "Ant" and "Apache Software
 *    Foundation" must not be used to endorse or promote products derived
 *    from this software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache"
 *    nor may "Apache" appear in their names without prior written
 *    permission of the Apache Group.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package edu.umd.cs.findbugs.anttask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

import edu.umd.cs.findbugs.ExitCodes;

/**
 * FindBugs in Java class files. This task can take the following arguments:
 * <ul>
 * <li>adjustExperimental (boolean default false)
 * <li>adjustPriority (passed to -adjustPriority)
 * <li>applySuppression (exclude any warnings that match a suppression filter
 * supplied in a project file)
 * <li>auxAnalyzepath (class, jar, zip files or directories containing classes
 * to analyze)
 * <li>auxClasspath (classpath or classpathRef)
 * <li>baselineBugs (xml file containing baseline bugs)
 * <li>class (class, jar, zip or directory containing classes to analyze)
 * <li>classpath (classpath for running FindBugs)
 * <li>cloud (cloud id)
 * <li>conserveSpace (boolean - default false)</li>
 * <li>debug (boolean default false)
 * <li>effort (enum min|default|max)</li>
 * <li>excludeFilter (filter filename)
 * <li>failOnError (boolean - default false)
 * <li>home (findbugs install dir)
 * <li>includeFilter (filter filename)
 * <li>maxRank (maximum rank issue to be reported)
 * <li>jvm (Set the command used to start the VM)
 * <li>jvmargs (any additional jvm arguments)
 * <li>omitVisitors (collection - comma seperated)
 * <li>onlyAnalyze (restrict analysis to find bugs to given comma-separated list
 * of classes and packages - See the textui argument description for details)
 * <li>output (enum text|xml|xml:withMessages|html - default xml)
 * <li>outputFile (name of output file to create)
 * <li>noClassOk (boolean default false)
 * <li>pluginList (list of plugin Jar files to load)
 * <li>projectFile (project filename)
 * <li>projectName (project name, for display in generated HTML)
 * <li>quietErrors (boolean - default false)
 * <li>relaxed (boolean - default false)
 * <li>reportLevel (enum experimental|low|medium|high)
 * <li>sort (boolean default true)
 * <li>stylesheet (name of stylesheet to generate HTML: default is
 * "default.xsl")
 * <li>systemProperty (a system property to set)
 * <li>timestampNow (boolean - default false)
 * <li>visitors (collection - comma seperated)
 * <li>chooseVisitors (selectively enable/disable visitors)
 * <li>workHard (boolean default false)
 * </ul>
 * Of these arguments, the <b>home</b> is required. <b>projectFile</b> is
 * required if nested &lt;class&gt; or &lt;auxAnalyzepath&gt elements are not
 * specified. the &lt;class&gt; tag defines the location of either a class, jar
 * file, zip file, or directory containing classes.
 * <p>
 * 
 * @author Mike Fagan <a href="mailto:mfagan@tde.com">mfagan@tde.com</a>
 * @author Michael Tamm <a
 *         href="mailto:mail@michaeltamm.de">mail@michaeltamm.de</a>
 * @author Scott Wolk
 * @version $Revision: 1.56 $
 * 
 * @since Ant 1.5
 * 
 * @ant.task category="utility"
 */

public class FindBugsTask extends AbstractFindBugsTask {

    private String effort;

    private boolean conserveSpace;

    private boolean sorted = true;

    private boolean timestampNow = true;

    private boolean quietErrors;

    private String warningsProperty;

    private String cloudId;

    private int maxRank;

    private String projectName;

    private boolean workHard;

    private boolean relaxed;

    private boolean adjustExperimental;

    private String adjustPriority;

    private File projectFile;

    private File baselineBugs;

    private boolean applySuppression;

    private File excludeFile;

    private File includeFile;

    private Path auxClasspath;

    private Path auxAnalyzepath;

    private Path sourcePath;

    private String outputFormat = "xml";

    private String reportLevel;

    private String visitors;

    private String chooseVisitors;

    private String omitVisitors;

    private String outputFileName;

    private String stylesheet;

    private List<ClassLocation> classLocations = new ArrayList<ClassLocation>();

    private String onlyAnalyze;

    private boolean noClassOk;

    private List<FileSet> filesets = new ArrayList<FileSet>();

    public FindBugsTask() {
        super("edu.umd.cs.findbugs.FindBugs2");
    }

    // define the inner class to store class locations
    public static class ClassLocation {
        File classLocation = null;

        public void setLocation(File location) {
            classLocation = location;
        }

        public File getLocation() {
            return classLocation;
        }

        @Override
        public String toString() {
            return classLocation != null ? classLocation.toString() : "";
        }

    }

    /**
     * Set the workHard flag.
     * 
     * @param workHard
     *            true if we want findbugs to run with workHard option enabled
     */
    public void setWorkHard(boolean workHard) {
        this.workHard = workHard;
    }

    /**
     * Set the noClassOk flag.
     * 
     * @param noClassOk
     *            true if we should generate no-error output if no classfiles
     *            are specified
     */
    public void setNoClassOk(boolean noClassOk) {
        this.noClassOk = noClassOk;
    }

    /**
     * Set the relaxed flag.
     * 
     * @param relaxed
     *            true if we want findbugs to run with relaxed option enabled
     */
    public void setRelaxed(boolean relaxed) {
        this.relaxed = relaxed;
    }

    /**
     * Set the adjustExperimental flag
     * 
     * @param adjustExperimental
     *            true if we want experimental bug patterns to have lower
     *            priority
     */
    public void setAdjustExperimental(boolean adjustExperimental) {
        this.adjustExperimental = adjustExperimental;
    }

    public void setAdjustPriority(String adjustPriorityString) {
        this.adjustPriority = adjustPriorityString;
    }

    /**
     * Set the specific visitors to use
     */
    public void setVisitors(String commaSeperatedString) {
        this.visitors = commaSeperatedString;
    }

    /**
     * Set the specific visitors to use
     */
    public void setChooseVisitors(String commaSeperatedString) {
        this.chooseVisitors = commaSeperatedString;
    }

    /**
     * Set the specific visitors to use
     */
    public void setOmitVisitors(String commaSeperatedString) {
        this.omitVisitors = commaSeperatedString;
    }

    /**
     * Set the output format
     */
    public void setOutput(String format) {
        this.outputFormat = format;
    }

    /**
     * Set the stylesheet filename for HTML generation.
     */
    public void setStylesheet(String stylesheet) {
        this.stylesheet = stylesheet;
    }

    /**
     * Set the report level
     */
    public void setReportLevel(String level) {
        this.reportLevel = level;
    }

    /**
     * Set the sorted flag
     */
    public void setSort(boolean flag) {
        this.sorted = flag;
    }

    /**
     * Set the timestampNow flag
     */
    public void setTimestampNow(boolean flag) {
        this.timestampNow = flag;
    }

    /**
     * Set the quietErrors flag
     */
    public void setQuietErrors(boolean flag) {
        this.quietErrors = flag;
    }

    /**
     * Set the quietErrors flag
     */
    public void setApplySuppression(boolean flag) {
        this.applySuppression = flag;
    }

    /**
     * Tells this task to set the property with the given name to "true" when
     * bugs were found.
     */
    public void setWarningsProperty(String name) {
        this.warningsProperty = name;
    }

    /**
     * Set effort level.
     * 
     * @param effort
     *            the effort level
     */
    public void setEffort(String effort) {
        this.effort = effort;
    }

    public void setCloud(String cloudId) {
        this.cloudId = cloudId.trim();
    }

    public void setMaxRank(int maxRank) {
        this.maxRank = maxRank;
    }

    /**
     * Set project name
     * 
     * @param projectName
     *            the project name
     */
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    /**
     * Set the conserveSpace flag.
     */
    public void setConserveSpace(boolean flag) {
        this.conserveSpace = flag;
    }

    /**
     * Set the exclude filter file
     */
    public void setExcludeFilter(File filterFile) {
        if (filterFile != null && filterFile.length() > 0)

            this.excludeFile = filterFile;
        else
            this.excludeFile = null;
    }

    /**
     * Set the exclude filter file
     */
    public void setIncludeFilter(File filterFile) {
        if (filterFile != null && filterFile.length() > 0)
            this.includeFile = filterFile;
        else
            this.includeFile = null;
    }

    /**
     * Set the exclude filter file
     */
    public void setBaselineBugs(File baselineBugs) {
        if (baselineBugs != null && baselineBugs.length() > 0)
            this.baselineBugs = baselineBugs;
        else
            this.baselineBugs = null;
    }

    /**
     * Set the project file
     */
    public void setProjectFile(File projectFile) {
        this.projectFile = projectFile;
    }

    /**
     * the auxclasspath to use.
     */
    public void setAuxClasspath(Path src) {
        boolean nonEmpty = false;

        String[] elementList = src.list();
        for (String anElementList : elementList) {
            if (!anElementList.equals("")) {
                nonEmpty = true;
                break;
            }
        }

        if (nonEmpty) {
            if (auxClasspath == null) {
                auxClasspath = src;
            } else {
                auxClasspath.append(src);
            }
        }
    }

    /**
     * Path to use for auxclasspath.
     */
    public Path createAuxClasspath() {
        if (auxClasspath == null) {
            auxClasspath = new Path(getProject());
        }
        return auxClasspath.createPath();
    }

    /**
     * Adds a reference to a sourcepath defined elsewhere.
     */
    public void setAuxClasspathRef(Reference r) {
        Path path = createAuxClasspath();
        path.setRefid(r);
        path.toString(); // Evaluated for its side-effects (throwing a
                         // BuildException)
    }

    /**
     * the auxAnalyzepath to use.
     */
    public void setAuxAnalyzepath(Path src) {
        boolean nonEmpty = false;

        String[] elementList = src.list();
        for (String anElementList : elementList) {
            if (!anElementList.equals("")) {
                nonEmpty = true;
                break;
            }
        }

        if (nonEmpty) {
            if (auxAnalyzepath == null) {
                auxAnalyzepath = src;
            } else {
                auxAnalyzepath.append(src);
            }
        }
    }

    /**
     * Path to use for auxAnalyzepath.
     */
    public Path createAuxAnalyzepath() {
        if (auxAnalyzepath == null) {
            auxAnalyzepath = new Path(getProject());
        }
        return auxAnalyzepath.createPath();
    }

    /**
     * Adds a reference to a sourcepath defined elsewhere.
     */
    public void setAuxAnalyzepathRef(Reference r) {
        createAuxAnalyzepath().setRefid(r);
    }

    /**
     * the sourcepath to use.
     */
    public void setSourcePath(Path src) {
        if (sourcePath == null) {
            sourcePath = src;
        } else {
            sourcePath.append(src);
        }
    }

    /**
     * Path to use for sourcepath.
     */
    public Path createSourcePath() {
        if (sourcePath == null) {
            sourcePath = new Path(getProject());
        }
        return sourcePath.createPath();
    }

    /**
     * Adds a reference to a source path defined elsewhere.
     */
    public void setSourcePathRef(Reference r) {
        createSourcePath().setRefid(r);
    }

    /**
     * Add a class location
     */
    public ClassLocation createClass() {
        ClassLocation cl = new ClassLocation();
        classLocations.add(cl);
        return cl;
    }

    /**
     * Set name of output file.
     */
    public void setOutputFile(String outputFileName) {
        if (outputFileName != null && outputFileName.length() > 0)
            this.outputFileName = outputFileName;
    }

    /**
     * Set the packages or classes to analyze
     */
    public void setOnlyAnalyze(String filter) {
        this.onlyAnalyze = filter;
    }

    /**
     * Add a nested fileset of classes or jar files.
     */
    public void addFileset(FileSet fs) {
        filesets.add(fs);
    }

    /**
     * Check that all required attributes have been set
     */
    @Override
    protected void checkParameters() {
        super.checkParameters();

        if (projectFile == null && classLocations.size() == 0 && filesets.size() == 0 && auxAnalyzepath == null) {
            throw new BuildException("either projectfile, <class/>, <fileset/> or <auxAnalyzepath/> child "
                    + "elements must be defined for task <" + getTaskName() + "/>", getLocation());
        }

        if (cloudId != null && cloudId.contains(" "))
            throw new BuildException("cloudId must not contain spaces: '" + cloudId + "'");

        if (outputFormat != null
                && !(outputFormat.trim().equalsIgnoreCase("xml") || outputFormat.trim().equalsIgnoreCase("xml:withMessages")
                        || outputFormat.trim().equalsIgnoreCase("html") || outputFormat.trim().equalsIgnoreCase("text")
                        || outputFormat.trim().equalsIgnoreCase("xdocs") || outputFormat.trim().equalsIgnoreCase("emacs"))) {
            throw new BuildException("output attribute must be either " + "'text', 'xml', 'html', 'xdocs' or 'emacs' for task <"
                    + getTaskName() + "/>", getLocation());
        }

        if (reportLevel != null
                && !(reportLevel.trim().equalsIgnoreCase("experimental") || reportLevel.trim().equalsIgnoreCase("low")
                        || reportLevel.trim().equalsIgnoreCase("medium") || reportLevel.trim().equalsIgnoreCase("high"))) {
            throw new BuildException("reportlevel attribute must be either "
                    + "'experimental' or 'low' or 'medium' or 'high' for task <" + getTaskName() + "/>", getLocation());
        }

        // FindBugs allows both, so there's no apparent reason for this check
        // if ( excludeFile != null && includeFile != null ) {
        // throw new BuildException("only one of excludeFile and includeFile " +
        // " attributes may be used in task <" + getTaskName() + "/>",
        // getLocation());
        // }

        if (effort != null && !effort.equals("min") && !effort.equals("default") && !effort.equals("max")) {
            throw new BuildException("effort attribute must be one of 'min', 'default', or 'max'");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.anttask.AbstractFindBugsTask#beforeExecuteJavaProcess
     * ()
     */
    @Override
    protected void beforeExecuteJavaProcess() {
        log("Running FindBugs...");
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.anttask.AbstractFindBugsTask#afterExecuteJavaProcess
     * (int)
     */
    @Override
    protected void afterExecuteJavaProcess(int rc) {
        if ((rc & ExitCodes.ERROR_FLAG) != 0) {
            throw new BuildException("Execution of findbugs failed.");
        }
        if ((rc & ExitCodes.MISSING_CLASS_FLAG) != 0) {
            log("Classes needed for analysis were missing");
        }
        if (warningsProperty != null && (rc & ExitCodes.BUGS_FOUND_FLAG) != 0) {
            getProject().setProperty(warningsProperty, "true");
        }

        if (outputFileName != null) {
            log("Output saved to " + outputFileName);
        }
    }

    @Override
    protected void configureFindbugsEngine() {
        if (projectName != null) {
            addArg("-projectName");
            addArg(projectName);
        }
        if (adjustExperimental) {
            addArg("-adjustExperimental");
        }

        if (cloudId != null) {
            addArg("-cloud");
            addArg(cloudId);
        }
        if (conserveSpace) {
            addArg("-conserveSpace");
        }
        if (workHard) {
            addArg("-workHard");
        }
        if (effort != null) {
            addArg("-effort:" + effort);
        }
        if (maxRank > 0 && maxRank < 20) {
            addArg("-maxRank ");
            addArg(Integer.toString(maxRank));
        }
        if (adjustPriority != null) {
            addArg("-adjustPriority");
            addArg(adjustPriority);
        }

        if (sorted)
            addArg("-sortByClass");
        if (timestampNow)
            addArg("-timestampNow");

        if (outputFormat != null && !outputFormat.trim().equalsIgnoreCase("text")) {
            outputFormat = outputFormat.trim();
            String outputArg = "-";
            int colon = outputFormat.indexOf(':');
            if (colon >= 0) {
                outputArg += outputFormat.substring(0, colon).toLowerCase();
                outputArg += ":";
                outputArg += outputFormat.substring(colon + 1);
            } else {
                outputArg += outputFormat.toLowerCase();
                if (stylesheet != null) {
                    outputArg += ":";
                    outputArg += stylesheet.trim();
                }
            }
            addArg(outputArg);
        }
        if (quietErrors)
            addArg("-quiet");
        if (reportLevel != null)
            addArg("-" + reportLevel.trim().toLowerCase());
        if (projectFile != null) {
            addArg("-project");
            addArg(projectFile.getPath());
        }
        if (applySuppression) {
            addArg("-applySuppression");
        }

        if (baselineBugs != null) {
            addArg("-excludeBugs");
            addArg(baselineBugs.getPath());
        }
        if (excludeFile != null) {
            addArg("-exclude");
            addArg(excludeFile.getPath());
        }
        if (includeFile != null) {
            addArg("-include");
            addArg(includeFile.getPath());
        }
        if (visitors != null) {
            addArg("-visitors");
            addArg(visitors);
        }
        if (omitVisitors != null) {
            addArg("-omitVisitors");
            addArg(omitVisitors);
        }
        if (chooseVisitors != null) {
            addArg("-chooseVisitors");
            addArg(chooseVisitors);
        }

        if (auxClasspath != null) {
            try {
                // Try to dereference the auxClasspath.
                // If it throws an exception, we know it
                // has an invalid path entry, so we complain
                // and tolerate it.
                @SuppressWarnings("unused")
                String unreadReference = auxClasspath.toString();
                String auxClasspathString = auxClasspath.toString();
                if (auxClasspathString.length() > 100) {
                    addArg("-auxclasspathFromInput");
                    setInputString(auxClasspathString);
                } else {
                    addArg("-auxclasspath");
                    addArg(auxClasspathString);
                }
            } catch (Throwable t) {
                log("Warning: auxClasspath " + t + " not found.");
            }
        }
        if (sourcePath != null) {
            addArg("-sourcepath");
            addArg(sourcePath.toString());
        }
        if (outputFileName != null) {
            addArg("-outputFile");
            addArg(outputFileName);
        }
        if (relaxed) {
            addArg("-relaxed");
        }
        if (noClassOk) {
            addArg("-noClassOk");
        }
        if (onlyAnalyze != null) {
            addArg("-onlyAnalyze");
            addArg(onlyAnalyze);
        }

        addArg("-exitcode");
        for (ClassLocation classLocation : classLocations) {
            addArg(classLocation.toString());
        }

        for (FileSet fs : filesets) {
            DirectoryScanner ds = fs.getDirectoryScanner();
            for (String fileName : ds.getIncludedFiles()) {
                File file = new File(ds.getBasedir(), fileName);
                addArg(file.toString());
            }
        }

        if (auxAnalyzepath != null) {
            String[] result = auxAnalyzepath.toString().split(java.io.File.pathSeparator);
            for (int x = 0; x < result.length; x++) {
                addArg(result[x]);
            }
        }
    }
}

// vim:ts=4
