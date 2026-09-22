package dev.mars.qraft.controller.raft.storage;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/** Separate-JVM fixture that owns a real RaftLog directory lock until stdin closes. */
public final class DirectoryLockProcess {
    private DirectoryLockProcess() { }

    public static void main(String[] args) {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        try (FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory).syncEnabled(true).build())) {
            storage.open(directory).join();
            System.out.println("LOCKED " + directory);
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("LOCK_FAILED directory=" + directory
                    + "; another process may hold the storage lock: " + cause.getMessage());
            System.exit(73);
        }
    }
}
