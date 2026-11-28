package cn.jia.economy.service.impl;

import cn.jia.economy.service.EconomyScope;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import static java.nio.file.StandardOpenOption.*;

/** Offline-only entrypoint. No Spring context, network, credentials, DB, execution or approval writer. */
public final class GroupBudgetStudyCommand {
    private GroupBudgetStudyCommand(){}
    public static void main(String[] args){try{System.out.println(run(args));}catch(Exception failure){
        // Deserializer errors may contain private input. Never print source material.
        System.err.println("Group study failed: "+failure.getClass().getSimpleName());System.exit(1);
    }}
    static String run(String... args) throws IOException {
        boolean scoped=args.length>0&&args[0].startsWith("scoped-");
        boolean forecast=args.length==6&&("forecast".equals(args[0])||"scoped-forecast".equals(args[0]));
        if(!forecast&&!(args.length==8&&("compare".equals(args[0])||"scoped-compare".equals(args[0]))))throw new IllegalArgumentException("Invalid group study command");
        Path root=Path.of(args[1]).toRealPath();if(!Files.isDirectory(root)||!Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))throw new IOException("Expected private object directory");
        Function<String,String> objects=pin->{if(pin==null||!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid object pin");
            Path path=root.resolve(pin);try(var in=Files.newInputStream(path,READ,LinkOption.NOFOLLOW_LINKS)){
                return new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }catch(IOException e){throw new UncheckedIOException(e);}};
        var study=new FrozenCalibrationStudy(new ObjectMapper(),null);var scope=new EconomyScope(args[2],args[3]);
        cn.jia.economy.task.TaskCalibrationObservations.Frozen artifact;
        if(scoped){var generic=new FrozenScopedGroupStudy();artifact=forecast?generic.forecast(scope,args[4],args[5],objects):generic.compare(scope,args[4],args[5],args[6],args[7],objects);}
        else {var old=forecast?study.groupForecasts(scope,args[4],args[5],objects):study.freeze(study.compareGroups(scope,args[4],args[5],args[6],args[7],objects));artifact=new cn.jia.economy.task.TaskCalibrationObservations.Frozen(old.sha256(),old.json());}
        // Publish only a complete file. Replays accept identical bytes but cannot overwrite originals.
        Path tmp=Files.createTempFile(root,".study-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{try(var channel=FileChannel.open(tmp,WRITE)){var bytes=ByteBuffer.wrap(artifact.json().getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            try{Files.createLink(root.resolve(artifact.sha256()),tmp);}catch(FileAlreadyExistsException exists){if(!artifact.json().equals(objects.apply(artifact.sha256())))throw new IOException("Conflicting artifact");}
        }finally{Files.deleteIfExists(tmp);}
        try(var directory=FileChannel.open(root,READ)){directory.force(true);}return artifact.sha256();
    }
}
