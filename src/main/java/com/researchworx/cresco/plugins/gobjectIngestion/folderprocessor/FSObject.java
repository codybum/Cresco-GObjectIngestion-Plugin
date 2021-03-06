package com.researchworx.cresco.plugins.gobjectIngestion.folderprocessor;

import com.researchworx.cresco.library.messaging.MsgEvent;
import com.researchworx.cresco.library.utilities.CLogger;
import com.researchworx.cresco.plugins.gobjectIngestion.Plugin;
import com.researchworx.cresco.plugins.gobjectIngestion.objectstorage.ObjectEngine;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

public class FSObject implements Runnable {

    private final String transfer_watch_file;
    private final String transfer_status_file;
    private final String bucket_name;
    private final String staging_folder;
    private Plugin plugin;
    private CLogger logger;
    private MsgEvent me;
    private String pathStage;

    public FSObject(Plugin plugin) {
        this.plugin = plugin;
        this.logger = new CLogger(FSObject.class, plugin.getMsgOutQueue(), plugin.getRegion(),
                plugin.getAgent(), plugin.getPluginID(), CLogger.Level.Trace);
        this.pathStage = String.valueOf(plugin.pathStage);

        logger.trace("FStoObject instantiated");
        transfer_watch_file = plugin.getConfig().getStringParam("transfer_watch_file");
        logger.debug("\"pathstage" + pathStage + "\" --> \"transfer_watch_file\" from config [{}]", transfer_watch_file);
        transfer_status_file = plugin.getConfig().getStringParam("transfer_status_file");
        logger.debug("\"pathstage" + pathStage + "\" --> \"transfer_status_file\" from config [{}]", transfer_status_file);
        bucket_name = plugin.getConfig().getStringParam("raw_bucket");
        logger.debug("\"pathstage" + pathStage + "\" --> \"bucket_name\" from config [{}]", bucket_name);
        staging_folder = plugin.getConfig().getStringParam("stagingdirectory");
        logger.debug("\"pathstage" + pathStage + "\" --> \"stagingdirectory\" from config [{}]", staging_folder);
        me = plugin.genGMessage(MsgEvent.Type.INFO, "InPathPreProcessor instantiated");
        me.setParam("transfer_watch_file", transfer_watch_file);
        me.setParam("transfer_status_file", transfer_status_file);
        me.setParam("bucket_name", bucket_name);
        me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
        me.setParam("pathstage", pathStage);
        me.setParam("pstep", "1");

        plugin.sendMsgEvent(me);
    }

    @Override
    public void run() {
        logger.trace("Thread starting");
        try {
            logger.trace("Setting [PathProcessorActive] to true");
            Plugin.PathProcessorActive = true;
            ObjectEngine oe = new ObjectEngine(plugin);
            //logger.trace("Issuing [ObjectEngine].createBucket using [bucket_name = {}]", bucket_name);
            //oe.createBucket(bucket_name);
            File stagingDir = new File(staging_folder);
            if (!stagingDir.exists())
                if (!stagingDir.mkdirs()) {
                    logger.error("Failed to create staging directory. Exiting");
                    return;
                }
            logger.trace("Entering while-loop");
            while (Plugin.PathProcessorActive) {
                //message start of scan

                try {
                    Path dir = Plugin.pathQueue.poll();
                    if (dir != null) {
                        logger.info("Processing folder [{}]", dir.toString());
                        String status = transferStatus(dir, "transfer_ready_status");
                        if (status != null && status.equals("yes")) {
                            logger.trace("Transfer file exists, processing");

                            me = plugin.genGMessage(MsgEvent.Type.INFO, "Start Filesystem Scan");
                            me.setParam("transfer_watch_file", transfer_watch_file);
                            me.setParam("transfer_status_file", transfer_status_file);
                            me.setParam("bucket_name", bucket_name);
                            me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                            me.setParam("pathstage", pathStage);
                            me.setParam("pstep", "2");
                            plugin.sendMsgEvent(me);

                            processDir(dir);

                            //message end of scan
                            me = plugin.genGMessage(MsgEvent.Type.INFO, "End Filesystem Scan");
                            me.setParam("transfer_watch_file", transfer_watch_file);
                            me.setParam("transfer_status_file", transfer_status_file);
                            me.setParam("bucket_name", bucket_name);
                            me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                            me.setParam("pathstage", pathStage);
                            me.setParam("pstep", "3");
                            plugin.sendMsgEvent(me);

                        }
                    } else {

                        me = plugin.genGMessage(MsgEvent.Type.INFO, "Idle");
                        me.setParam("transfer_watch_file", transfer_watch_file);
                        me.setParam("transfer_status_file", transfer_status_file);
                        me.setParam("bucket_name", bucket_name);
                        me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                        me.setParam("pathstage", pathStage);
                        me.setParam("pstep", "3");
                        plugin.sendMsgEvent(me);

                        Thread.sleep(plugin.getConfig().getIntegerParam("scan_interval", 5000));
                    }
                } catch (Exception ex) {
                    logger.error("run : while\n{}", ExceptionUtils.getStackTrace(ex));
                    //message start of scan
                    me = plugin.genGMessage(MsgEvent.Type.ERROR, "Error during Filesystem scan");
                    me.setParam("transfer_watch_file", transfer_watch_file);
                    me.setParam("transfer_status_file", transfer_status_file);
                    me.setParam("bucket_name", bucket_name);
                    me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                    me.setParam("pathstage", pathStage);
                    me.setParam("error_message", ex.getMessage());
                    me.setParam("pstep", "2");
                    plugin.sendMsgEvent(me);
                    Thread.sleep(plugin.getConfig().getIntegerParam("scan_interval", 5000));
                }


            }
        } catch (Exception ex) {
            logger.error("run {}", ex.getMessage());
            me = plugin.genGMessage(MsgEvent.Type.ERROR, "Error Path Run");
            me.setParam("transfer_watch_file", transfer_watch_file);
            me.setParam("transfer_status_file", transfer_status_file);
            me.setParam("bucket_name", bucket_name);
            me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
            me.setParam("pathstage", pathStage);
            me.setParam("error_message", ex.getMessage());
            me.setParam("pstep", "2");
            plugin.sendMsgEvent(me);

        }
    }

    private String transferStatus(Path dir, String statusString) {
        if (!Files.exists(dir))
            return null;
        logger.debug("Call to transferStatus [dir = {}, statusString = {}]", dir.toString(), statusString);
        String status = "no";
        try {
            if (dir.toString().toLowerCase().endsWith(transfer_watch_file.toLowerCase()) &&
                    !dir.toString().toLowerCase().endsWith(transfer_status_file.toLowerCase())) {
                logger.trace("[dir] tail matches [transfer_watch_file]");
                logger.trace("Replacing [transfer_watch_file] with [transfer_status_file]");
                String tmpPath = dir.toString().replace(transfer_watch_file, transfer_status_file);
                logger.debug("Creating file [{}]", tmpPath);
                File f = new File(tmpPath);
                if (!f.exists()) {
                    logger.trace("File doesn't already exist");
                    if (createTransferFile(dir)) {
                        logger.info("Created new transferfile: " + tmpPath);
                    }
                }
            } else if (dir.toString().toLowerCase().endsWith(transfer_status_file.toLowerCase())) {
                logger.trace("[dir] tail matches [transfer_status_file]");
                try (BufferedReader br = new BufferedReader(new FileReader(dir.toString()))) {
                    logger.trace("Reading line from [transfer_status_file]");
                    String line = br.readLine();
                    while (line != null) {
                        if (line.contains("=")) {
                            logger.trace("Line contains \"=\"");
                            String[] sline = line.split("=");
                            logger.debug("Line split into {} and {}", sline[0], sline[1]);
                            if (sline[0].toLowerCase().equals(statusString) && sline[1].toLowerCase().equals("yes")) {
                                status = "yes";
                                logger.info("Status: {}={}", statusString, status);
                            }
                        }
                        line = br.readLine();
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("transferStatus : {}", ex.toString());
        }
        return status;
    }

    private boolean createTransferFile(Path dir) {
        logger.debug("Call to createTransferFile [dir = {}]", dir.toString());
        boolean isTransfer = false;
        try {
            logger.trace("Building file path");
            String tmpPath = dir.toString().replace(transfer_watch_file, transfer_status_file);
            logger.trace("Building lines array");
            List<String> lines = Arrays.asList("TRANSFER_READY_STATUS=YES", "TRANSFER_COMPLETE_STATUS=NO");
            logger.debug("[tmpPath = {}]", tmpPath);
            Path file = Paths.get(tmpPath);
            logger.trace("Writing lines to file at [tmpPath]");
            Files.write(file, lines, Charset.forName("UTF-8"));
            logger.trace("Completed writing to file");
            Plugin.pathQueue.offer(dir); //add to path for processing
            isTransfer = true;
        } catch (Exception ex) {
            logger.error("createTransferFile Error : {}", ex.getMessage());
        }
        return isTransfer;
    }

    private String getSampleList(String inDir) {
        String sampleList = null;
        try {
            if (!inDir.endsWith("/")) {
                inDir += "/";
            }
            ArrayList<String> samples = new ArrayList<>();
            logger.trace("Processing Sequence Directory : " + inDir);
            File file = new File(inDir);
            String[] directories = file.list((dir, name) -> new File(dir, name).isDirectory());

            List<String> subDirectories = new ArrayList<>();

            if (directories != null) {
                for (String subDir : directories) {
                    logger.trace("Searching for sub-directories of {}", inDir + "/" + subDir);
                    subDirectories.add(subDir);
                    File subFile = new File(inDir + "/" + subDir);
                    String[] subSubDirs = subFile.list((dir, name) -> new File(dir, name).isDirectory());
                    if (subSubDirs != null) {
                        for (String subSubDir : subSubDirs) {
                            logger.trace("Found sub-directory {}", inDir + "/" + subDir + "/" + subSubDir);
                            subDirectories.add(subDir + "/" + subSubDir);
                        }
                    }
                }
            }

            for (String subDirectory : subDirectories) {
                logger.trace("Processing Sample SubDirectory : " + subDirectory);
                String commands_main_filename = inDir + subDirectory + "/commands_main.sh";
                String config_files_directoryname = inDir + subDirectory + "/config_files";
                File commands_main = new File(commands_main_filename);
                File config_files = new File(config_files_directoryname);
                logger.trace("commands_main " + commands_main_filename + " exist : " + commands_main.exists());
                logger.trace("config_files " + config_files_directoryname + " exist : " + config_files.exists());

                if (commands_main.exists() && !commands_main.isDirectory() &&
                        config_files.exists() && config_files.isDirectory()) {
                    // do something
                    samples.add(subDirectory);
                    logger.trace("Found Sample: " + commands_main_filename + " and " + config_files_directoryname);
                }
            }
            //build list
            if (!samples.isEmpty()) {
                sampleList = "";
                for (String tSample : samples) {
                    sampleList += tSample + ",";
                }
                sampleList = sampleList.substring(0, sampleList.length() - 1);
            }
        } catch (Exception ex) {
            logger.error("getSameplList : " + ex.getMessage());
        }
        return sampleList;
    }

    private void processDir(Path dir) {
        logger.debug("Call to processDir [dir = {}]", dir.toString());

        String inDir = dir.toString();
        if (inDir.contains("\\")) {
            inDir = inDir.replaceAll("\\\\", "/");
        }
        inDir = inDir.substring(0, inDir.length() - transfer_status_file.length() - 1);
        logger.debug("[inDir = {}]", inDir);

        String outDir = inDir;
        outDir = outDir.substring(outDir.lastIndexOf("/") + 1, outDir.length());
        String seqId = outDir;
        logger.debug("[outDir = {}]", outDir);

        //File seqStageDir = Paths.get(staging_folder, seqId).toFile();
        Path seqStageDir = Paths.get(staging_folder, seqId);

        logger.info("Start processing directory {}", outDir);

        String status = transferStatus(dir, "transfer_complete_status");
        List<String> filterList = new ArrayList<>();
        logger.trace("Adding [transfer_status_file] to [filterList]");
        filterList.add(transfer_status_file);


        if (status.equals("no")) {
            sendUpdateInfoMessage(seqId, null, null, 1,
                    "Discovered for upload");
            try {
                logger.info("Copying sequence to staging folder [{}] -> [{}]",
                        inDir, seqStageDir);
                if (Files.exists(seqStageDir)) {
                    sendUpdateInfoMessage(seqId, null, null, 1,
                            "Deleting existing file(s) from staging directory");
                    deleteFolder(seqStageDir);
                }
                sendUpdateInfoMessage(seqId, null, null, 1,
                        "Moving files from watch directory to staging directory");
                //copyFolderContents(new File(inDir), seqStageDir);
                if (!moveFolder(inDir, seqStageDir.toString()))
                    return;
                //sendUpdateInfoMessage(seqId, null, null, 1,
                //        "Deleting leftover folder(s) from  watch directory");
                //deleteFolder(Paths.get(inDir));
            } catch (IOException e) {
                //logger.error("Failed to move sequence to staging directory [{}] -> [{}]\n" + ExceptionUtils.getStackTrace(e), inDir, seqStageDir);
                sendUpdateErrorMessage(seqId, null, null, 1, "Failed to move sequence to staging directory: " + ExceptionUtils.getStackTrace(e));
                return;
            }

            /*me = plugin.genGMessage(MsgEvent.Type.INFO, "Start transfer directory");
            me.setParam("seq_id", seqId);
            me.setParam("pathstage", pathStage);
            me.setParam("sstep", "1");
            plugin.sendMsgEvent(me);*/
            sendUpdateInfoMessage(seqId, null, null, 1,
                    "Starting transfer from staging to object store");


            logger.debug("[status = \"no\"]");
            ObjectEngine oe = new ObjectEngine(plugin);
            if (oe.uploadBaggedDirectory(bucket_name, seqStageDir.toString(), "", outDir,
                    null,null, "1")) {
                if (setTransferFile(seqStageDir.resolve(transfer_status_file))) {
                /*if (new File(inDir).exists()) {
                    try {
                        //logger.info("Cleaning up uploaded sequence [{}]", inDir);
                        sendUpdateInfoMessage(seqId, null, null, 1,
                                "Final cleanup in watch directory");
                        //FileUtils.deleteDirectory(new File(inDir));
                        deleteFolder(new File(inDir).toPath());
                    } catch (IOException e) {
                        //logger.error("Failed to remove sequence directory [{}]" + ExceptionUtils.getStackTrace(e), inDir);
                        sendUpdateErrorMessage(seqId, null, null, 1,
                                "Failed to remove some files from watch directory, please clean manually");
                    }
                }*/
                    logger.debug("Directory Transferred [inDir = {}, outDir = {}]", inDir, outDir);
                    me = plugin.genGMessage(MsgEvent.Type.INFO, "Directory Transferred");
                    me.setParam("indir", inDir);
                    me.setParam("outdir", outDir);
                    me.setParam("seq_id", seqId);
                    me.setParam("transfer_watch_file", transfer_watch_file);
                    me.setParam("transfer_status_file", transfer_status_file);
                    me.setParam("bucket_name", bucket_name);
                    me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                    me.setParam("pathstage", pathStage);
                    //if pathstage 3 we need to submit jobs for processing
                    logger.trace("pathStage = " + pathStage);
                    if (pathStage.equals("3")) {
                        logger.trace("Sample Directory: " + inDir);
                        String sampleList = getSampleList(inDir);

                        if (sampleList != null) {
                            logger.trace("Samples : " + sampleList);
                            me.setParam("sample_list", sampleList);
                        } else {
                            me.setParam("sample_list", "");
                        }
                    }
                    me.setParam("sstep", "2");
                    plugin.sendMsgEvent(me);
                    //end
                } else {
                    logger.error("Directory Transfer Failed [inDir = {}, outDir = {}]", inDir, outDir);
                    me = plugin.genGMessage(MsgEvent.Type.ERROR, "Failed Directory Transfer");
                    me.setParam("indir", inDir);
                    me.setParam("outdir", outDir);
                    me.setParam("seq_id", seqId);
                    me.setParam("transfer_watch_file", transfer_watch_file);
                    me.setParam("transfer_status_file", transfer_status_file);
                    me.setParam("bucket_name", bucket_name);
                    me.setParam("endpoint", plugin.getConfig().getStringParam("endpoint"));
                    me.setParam("pathstage", pathStage);
                    me.setParam("sstep", "2");
                    plugin.sendMsgEvent(me);
                }
            }
        } /*else if (status.equals("yes")) {
            logger.trace("[status = \"yes\"]");
            if (oe.isSyncDir(bucket_name, outDir, inDir, filterList)) {
                logger.debug("Directory Sycned inDir={} outDir={}", inDir, outDir);
            }
        }*/
    }

    private void setTransferFileMD5(Path dir, Map<String, String> md5map) {
        logger.debug("Call to setTransferFileMD5 [dir = {}, md5map = {}", dir.toString(), md5map.toString());
        try {
            String watchDirectoryName = plugin.getConfig().getStringParam("watchdirectory");
            logger.debug("Grabbing [pathstage" + pathStage + " --> watchdirectory] from config [{}]", watchDirectoryName);
            if (dir.toString().toLowerCase().endsWith(transfer_status_file.toLowerCase())) {
                logger.trace("[dir] ends with [transfer_status_file]");
                PrintWriter out = null;
                try {
                    logger.trace("Opening [dir] to write");
                    out = new PrintWriter(new BufferedWriter(new FileWriter(dir.toString(), true)));
                    for (Map.Entry<String, String> entry : md5map.entrySet()) {
                        String md5file = entry.getKey().replace(watchDirectoryName, "");
                        if (md5file.startsWith("/")) {
                            md5file = md5file.substring(1);
                        }
                        out.write(md5file + ":" + entry.getValue() + "\n");
                        logger.debug("[md5file = {}, entry = {}] written", md5file, entry.getValue());
                    }
                } finally {
                    try {
                        assert out != null;
                        out.flush();
                        out.close();
                    } catch (AssertionError e) {
                        logger.error("setTransferFileMd5 - PrintWriter was pre-emptively shutdown");
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("setTransferFile : {}", ex.getMessage());
        }
    }

    private boolean setTransferFile(Path dir) {
        logger.debug("Call to setTransferFile [dir = {}]", dir.toString().replace("\\", "\\\\"));
        boolean isSet = false;
        try {
            if (dir.toString().toLowerCase().endsWith(transfer_status_file.toLowerCase())) {
                logger.trace("[dir] ends with [transfer_status_file]");
                List<String> slist = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(new FileReader(dir.toString()))) {
                    String line = br.readLine();
                    logger.trace("Grabbing a line from [dir]");
                    while (line != null) {
                        if (line.contains("=")) {
                            logger.trace("Line contains \"=\"");
                            String[] sline = line.split("=");
                            logger.debug("Line split into {} and {}", sline[0], sline[1]);
                            if (sline[0].toLowerCase().equals("transfer_complete_status")) {
                                logger.trace("[sline[0] == \"transfer_complete_status\"]");
                                slist.add("TRANSFER_COMPLETE_STATUS=YES");
                            } else {
                                logger.trace("[sline[0] != \"transfer_complete_status\"]");
                                slist.add(line);
                            }
                        }
                        line = br.readLine();
                    }
                }
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(new File(dir.toString()).toString()))) {
                    logger.trace("Writing to [dir]");
                    for (String line : slist) {
                        bw.write(line + "\n");
                    }
                }
                logger.trace("Updating status to complete");
                String status = transferStatus(dir, "transfer_complete_status");
                if (status.equals("yes")) {
                    isSet = true;
                }
            }
        } catch (Exception ex) {
            logger.error("setTransferFile {}", ex.getMessage());
        }
        return isSet;
    }



    /**
     * Copies the files from one directory to another
     * @param src Source directory to copy files from
     * @param dst Destination directory to copy files to
     * @throws IOException
     */
    private void copyFolderContents(File src, File dst) throws IOException {
        //logger.trace("Call to copyFolderContents({},{})", src.getAbsolutePath(), dst.getAbsolutePath());
        if (src.toString().endsWith(transfer_status_file)) {
            Files.delete(src.toPath());
            return;
        }
        if (src.isDirectory()) {
            if (!dst.exists())
                dst.mkdir();
            String files[] = src.list();
            for (String file : files) {
                File srcFile = new File(src, file);
                File destFile = new File(dst, file);
                copyFolderContents(srcFile,destFile);
            }
        } else
            Files.move(Paths.get(src.toURI()), Paths.get(dst.toURI()));
    }

    private boolean moveFolder(String srcPathString, String dstPathString) {
        try {
            Path srcPath = Paths.get(srcPathString);
            if (!Files.exists(srcPath)) {
                logger.error("Folder to move [{}] does not exist", srcPathString.replace("\\", "\\\\"));
                return false;
            }
            Path dstPath = Paths.get(dstPathString);
            Files.deleteIfExists(dstPath);
            long started = System.currentTimeMillis();
            Files.move(srcPath, dstPath, ATOMIC_MOVE);
            logger.trace("Moved folder in {}ms", (System.currentTimeMillis() - started));
            return true;
        } catch (IOException e) {
            logger.error("Failed to move folder : {}", ExceptionUtils.getStackTrace(e).replace("\\", "\\\\"));
            return false;
        }
    }

    /**
     * Deletes an entire folder structure
     * @param folder Path of the folder to delete
     * @throws IOException Thrown from sub-routines
     */
    private void deleteFolder(Path folder) throws IOException {
        logger.trace("Call to deleteFolder({})", folder.toAbsolutePath());
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                try {
                    FileDeleteStrategy.FORCE.delete(dir.toFile());
                } catch (FileNotFoundException e) { }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void sendUpdateInfoMessage(String seqId, String sampleId, String reqId, int stepInt, String message) {
        String step = String.valueOf(stepInt);
        if (!message.equals("Idle"))
            logger.info("{}", message);
        MsgEvent msgEvent = plugin.genGMessage(MsgEvent.Type.INFO, message);
        msgEvent.setParam("pathstage", String.valueOf(plugin.pathStage));
        msgEvent.setParam("seq_id", seqId);
        if (sampleId != null) {
            msgEvent.setParam("sample_id", sampleId);
            msgEvent.setParam("ssstep", step);
        } else if (seqId != null)
            msgEvent.setParam("sstep", step);
        else
            msgEvent.setParam("pstep", step);
        if (reqId != null)
            msgEvent.setParam("req_id", reqId);
        plugin.sendMsgEvent(msgEvent);
    }

    private void sendUpdateErrorMessage(String seqId, String sampleId, String reqId, int stepInt, String message) {
        String step = String.valueOf(stepInt);
        logger.error("{}", message);
        MsgEvent msgEvent = plugin.genGMessage(MsgEvent.Type.ERROR, "");
        msgEvent.setParam("pathstage", String.valueOf(plugin.pathStage));
        msgEvent.setParam("error_message", message);
        msgEvent.setParam("seq_id", seqId);
        if (sampleId != null) {
            msgEvent.setParam("sample_id", sampleId);
            msgEvent.setParam("ssstep", step);
        } else if (seqId != null)
            msgEvent.setParam("sstep", step);
        else
            msgEvent.setParam("pstep", step);
        if (reqId != null)
            msgEvent.setParam("req_id", reqId);
        plugin.sendMsgEvent(msgEvent);
    }
}

