//
// JODConverter - Java OpenDocument Converter
// Copyright 2004-2012 Mirko Nasato and contributors
//
// JODConverter is Open Source software, you can redistribute it and/or
// modify it under either (at your option) of the following licenses
//
// 1. The GNU Lesser General Public License v3 (or later)
//    -> http://www.gnu.org/licenses/lgpl-3.0.txt
// 2. The Apache License, Version 2.0
//    -> http://www.apache.org/licenses/LICENSE-2.0.txt
//
// 2012 - Alfresco Software, Ltd.
// Alfresco Software has modified source of this file
// The details of changes can be found in the github repo: https://github.com/Alfresco/alfresco-jodconverter
package org.artofsolving.jodconverter.office;

import static org.artofsolving.jodconverter.process.ProcessManager.PID_NOT_FOUND;
import static org.artofsolving.jodconverter.process.ProcessManager.PID_UNKNOWN;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.artofsolving.jodconverter.process.ProcessManager;
import org.artofsolving.jodconverter.process.ProcessQuery;
import org.artofsolving.jodconverter.util.PlatformUtils;

class OfficeProcess {

    private final File officeHome;
    private final UnoUrl unoUrl;
    private final String[] runAsArgs;
    private final File templateProfileDir;
    private final File instanceProfileDir;
    private final ProcessManager processManager;

    private Process process;
    private long pid = PID_UNKNOWN;
    private final int searchIterations = 10;
    // Sleep time between retries in ms
    private final int searchSleep = 50;

    private final Logger logger = Logger.getLogger(getClass().getName());

    public OfficeProcess(File officeHome, UnoUrl unoUrl, String[] runAsArgs, File templateProfileDir, File workDir, ProcessManager processManager) {
        this.officeHome = officeHome;
        this.unoUrl = unoUrl;
        this.runAsArgs = runAsArgs;
        this.templateProfileDir = templateProfileDir;
        this.instanceProfileDir = getInstanceProfileDir(workDir, unoUrl);
        this.processManager = processManager;
    }

    public void start() throws IOException, InterruptedException {
        start(false);
    }

    public void start(boolean restart) throws IOException, InterruptedException {
        ProcessQuery processQuery = new ProcessQuery("soffice.bin", unoUrl.getAcceptString());
        long existingPid = processManager.findPid(processQuery);
    	if (!(existingPid == PID_NOT_FOUND || existingPid == PID_UNKNOWN)) {
			throw new IllegalStateException(String.format("a process with acceptString '%s' is already running; pid %d",
			        unoUrl.getAcceptString(), existingPid));
        }
    	if (!restart) {
    	    prepareInstanceProfileDir();
    	}
        List<String> command = new ArrayList<String>();
        File executable = OfficeUtils.getOfficeExecutable(officeHome);
        if (runAsArgs != null) {
        	command.addAll(Arrays.asList(runAsArgs));
        }
        command.add(executable.getAbsolutePath());
        boolean libreOffice3Dot5 = isLibreOffice3Dot5();
        if (libreOffice3Dot5)
        {
            command.add("--accept=" + unoUrl.getAcceptString() + ";urp;");
            if (PlatformUtils.isMac() && !isLibreOffice3Dot6())
            {
                command.add("--env:UserInstallation=" + OfficeUtils.toUrl(instanceProfileDir));
            }
            else
            {
                command.add("-env:UserInstallation=" + OfficeUtils.toUrl(instanceProfileDir));
            }
            command.add("--headless");
            command.add("--nocrashreport");
            command.add("--nodefault");
            command.add("--nofirststartwizard");
            command.add("--nolockcheck");
            command.add("--nologo");
            command.add("--norestore");
            logger.info("Using GNU based LibreOffice command"+(PlatformUtils.isMac() ? " on Mac" : "")+": "+command);
            logger.info("Using GNU based LibreOffice "+
                    (isLibreOffice3Dot6() ? "3.6" : "3.5")+" command"+
                    (PlatformUtils.isMac() ? " on Mac" : "")+": "+command);
        }
        else
        {
            command.add("-accept=" + unoUrl.getAcceptString() + ";urp;");
            command.add("-env:UserInstallation=" + OfficeUtils.toUrl(instanceProfileDir));
            command.add("-headless");
            command.add("-nocrashreport");
            command.add("-nodefault");
            command.add("-nofirststartwizard");
            command.add("-nolockcheck");
            command.add("-nologo");
            command.add("-norestore");
            logger.info("Using original OpenOffice command: "+command);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (PlatformUtils.isWindows()) {
            addBasisAndUrePaths(processBuilder);
        }
        if (PlatformUtils.isMac())
        {
            Map<String, String> env = processBuilder.environment();
            env.remove("DYLD_LIBRARY_PATH");
            logger.info("Removing $DYLD_LIBRARY_PATH from the environment so that LibreOffice/OpenOffice will start on Mac.");
        }
        logger.info(String.format("starting process with acceptString '%s' and profileDir '%s'", unoUrl, instanceProfileDir));
        process = processBuilder.start();
        for(int i = 0; i < searchIterations; i++)
        {
            pid = processManager.findPid(processQuery);
            if (pid != PID_NOT_FOUND && pid != PID_UNKNOWN)
            {
                break;
            }
            Thread.sleep(searchSleep);
        }
        if (pid == PID_NOT_FOUND) {
            throw new IllegalStateException(String.format("process with acceptString '%s' started but its pid could not be found",
                    unoUrl.getAcceptString()));
        }
        logger.info("started process" + (pid != PID_UNKNOWN ? "; pid = " + pid : ""));
    }

    private File getInstanceProfileDir(File workDir, UnoUrl unoUrl) {
        String dirName = ".jodconverter_" + unoUrl.getAcceptString().replace(',', '_').replace('=', '-');
        return new File(workDir, dirName);
    }

    private void prepareInstanceProfileDir() throws OfficeException {
        if (instanceProfileDir.exists()) {
            logger.warning(String.format("profile dir '%s' already exists; deleting", instanceProfileDir));
            deleteProfileDir();
        }
        if (templateProfileDir != null) {
            try {
                FileUtils.copyDirectory(templateProfileDir, instanceProfileDir);
            } catch (IOException ioException) {
                throw new OfficeException("failed to create profileDir", ioException);
            }
        }
    }

    public void deleteProfileDir() {
        if (instanceProfileDir != null) {
            try {
                FileUtils.deleteDirectory(instanceProfileDir);
            } catch (IOException ioException) {
                File oldProfileDir = new File(instanceProfileDir.getParentFile(), instanceProfileDir.getName() + ".old." + System.currentTimeMillis());
                if (instanceProfileDir.renameTo(oldProfileDir)) {
                    logger.warning("could not delete profileDir: " + ioException.getMessage() + "; renamed it to " + oldProfileDir);
                } else {
                    logger.severe("could not delete profileDir: " + ioException.getMessage());
                }
            }
        }
    }
    
    private boolean isLibreOffice3Dot5()
    {
        return
            !new File(officeHome, "basis-link").isFile() &&
            (new File(officeHome, "ure-link").isFile() || new File(officeHome, "ure-link").isDirectory());
    }

    private boolean isLibreOffice3Dot6()
    {
        return isLibreOffice3Dot5() && new File(officeHome, "NOTICE").isFile();
    }

    private void addBasisAndUrePaths(ProcessBuilder processBuilder) throws IOException {
        File ureBin = null;
        File basisProgram = null;

        // see http://wiki.services.openoffice.org/wiki/ODF_Toolkit/Efforts/Three-Layer_OOo
        File basisLink = new File(officeHome, "basis-link");
        if (!basisLink.isFile()) {
            // check the case with LibreOffice 3.5 home
            File ureLink = new File(officeHome, "ure-link");
            if (!ureLink.isFile()) {
                logger.fine("no %OFFICE_HOME%/basis-link found; assuming it's OOo 2.x and we don't need to append URE and Basic paths");
                return;
            }
            ureBin = new File(new File(officeHome, FileUtils.readFileToString(ureLink).trim()), "bin");
        } else {
            String basisLinkText = FileUtils.readFileToString(basisLink).trim();
            File basisHome = new File(officeHome, basisLinkText);
            basisProgram = new File(basisHome, "program");
            File ureLink = new File(basisHome, "ure-link");
            String ureLinkText = FileUtils.readFileToString(ureLink).trim();
            File ureHome = new File(basisHome, ureLinkText);
            ureBin = new File(ureHome, "bin");
        }
        
        Map<String,String> environment = processBuilder.environment();
        // Windows environment variables are case insensitive but Java maps are not :-/
        // so let's make sure we modify the existing key
        String pathKey = "PATH";
        for (String key : environment.keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                pathKey = key;
            }
        }
        String path = environment.get(pathKey) + ";" + ureBin.getAbsolutePath();
        if (basisProgram != null) {
            path += ";" + basisProgram.getAbsolutePath();
        }
        
        logger.fine(String.format("setting %s to \"%s\"", pathKey, path));
        environment.put(pathKey, path);
    }

    public boolean isRunning() {
        if (process == null) {
            return false;
        }
        return getExitCode() == null;
    }

    private class ExitCodeRetryable extends Retryable {
        
        private int exitCode;
        
        protected void attempt() throws TemporaryException, Exception {
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException illegalThreadStateException) {
                throw new TemporaryException(illegalThreadStateException);
            }
        }
        
        public int getExitCode() {
            return exitCode;
        }

    }

    public Integer getExitCode() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException exception) {
            return null;
        }
    }

    public int getExitCode(long retryInterval, long retryTimeout) throws RetryTimeoutException {
        try {
            ExitCodeRetryable retryable = new ExitCodeRetryable();
            retryable.execute(retryInterval, retryTimeout);
            return retryable.getExitCode();
        } catch (RetryTimeoutException retryTimeoutException) {
            throw retryTimeoutException;
        } catch (Exception exception) {
            throw new OfficeException("could not get process exit code", exception);
        }
    }

    public int forciblyTerminate(long retryInterval, long retryTimeout) throws IOException, RetryTimeoutException {
        logger.info(String.format("trying to forcibly terminate process: '" + unoUrl + "'" + (pid != PID_UNKNOWN ? " (pid " + pid  + ")" : "")));
        processManager.kill(process, pid);
        return getExitCode(retryInterval, retryTimeout);
    }

}
