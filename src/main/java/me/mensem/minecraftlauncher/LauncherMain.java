package me.mensem.minecraftlauncher;

import javafx.application.Application;
import javafx.application.Platform;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public final class LauncherMain {
    private static MinecraftLauncher appInstance;

    private LauncherMain() {
    }

    public static void main(String[] args) {
        try {
            Path logDir = SingleInstanceLock.getLockDir();
            Files.createDirectories(logDir);
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(logDir.resolve("launcher_error.log").toFile(), true)));
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(logDir.resolve("launcher_out.log").toFile(), true)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try (SingleInstanceLock lock = SingleInstanceLock.acquire()) {
            if (lock == null) {
                // Already running. Forward callback if present.
                if (args.length > 0 && args[0].startsWith(LauncherConstants.GREYTON_LAUNCHER_CALLBACK_PROTOCOL)) {
                    try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), LauncherConstants.IPC_PORT)) {
                        socket.getOutputStream().write((args[0] + "\n").getBytes());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return;
            }

            // Start IPC listener
            startIPCListener();

            Application.launch(MinecraftLauncher.class, args);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void startIPCListener() {
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(LauncherConstants.IPC_PORT, 0, InetAddress.getByName("127.0.0.1"))) {
                while (true) {
                    try (Socket socket = server.accept();
                         Scanner scanner = new Scanner(socket.getInputStream())) {
                        if (scanner.hasNextLine()) {
                            String uri = scanner.nextLine();
                            if (appInstance != null) {
                                Platform.runLater(() -> appInstance.processAuthCallback(uri));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "ipc-listener").start();
    }

    public static void setAppInstance(MinecraftLauncher app) {
        appInstance = app;
    }
    // ... SingleInstanceLock class remains same ...

    private static final class SingleInstanceLock implements AutoCloseable {
        private final RandomAccessFile lockFile;
        private final FileChannel channel;
        private final FileLock lock;

        private SingleInstanceLock(RandomAccessFile lockFile, FileChannel channel, FileLock lock) {
            this.lockFile = lockFile;
            this.channel = channel;
            this.lock = lock;
        }

        static SingleInstanceLock acquire() throws IOException {
            Path lockDir = getLockDir();
            Files.createDirectories(lockDir);

            RandomAccessFile lockFile = new RandomAccessFile(lockDir.resolve("launcher.lock").toFile(), "rw");
            FileChannel channel = lockFile.getChannel();

            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    closeQuietly(channel, lockFile);
                    return null;
                }

                lockFile.setLength(0);
                lockFile.writeBytes(Long.toString(ProcessHandle.current().pid()));
                return new SingleInstanceLock(lockFile, channel, lock);
            } catch (OverlappingFileLockException e) {
                closeQuietly(channel, lockFile);
                return null;
            } catch (IOException e) {
                closeQuietly(channel, lockFile);
                throw e;
            }
        }

        private static Path getLockDir() {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "Pura Launcher");
            }
            return Path.of(System.getProperty("user.home"), ".pura-launcher");
        }

        private static void closeQuietly(FileChannel channel, RandomAccessFile lockFile) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            try {
                lockFile.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
            lockFile.close();
        }
    }
}
