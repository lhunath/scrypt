// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.jni;

import com.google.common.base.Splitter;
import com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A native library loader that will extract and load a shared library contained in a jar.
 * This loader will attempt to detect the {@link Platform platform} (CPU architecture and OS)
 * it is running on and load the appropriate shared library.
 *
 * Given a library path and name this loader looks for a native library with path
 * [libraryPath]/[arch]/[os]/lib[name].[ext]
 *
 * @author Will Glozer
 */
public class JarLibraryLoader implements LibraryLoader {

    private final static Logger LOGGER = LoggerFactory.getLogger(JarLibraryLoader.class);

    private static final String NESTED_DELIMITER = "!";
    private static final Splitter SPLITTER = Splitter.on(NESTED_DELIMITER);
    private static final int NESTED_JAR_INDEX = 1;

    private final CodeSource codeSource;
    private final String libraryPath;

    /**
     * Initialize a new instance that looks for shared libraries located in the same jar
     * as this class and with a path starting with {@code lib}.
     */
    public JarLibraryLoader() {
        this(JarLibraryLoader.class.getProtectionDomain().getCodeSource(), "lib");
    }

    /**
     * Initialize a new instance that looks for shared libraries located in the specified
     * directory of the supplied code source.
     *
     * @param codeSource    Code source containing shared libraries.
     * @param libraryPath   Path prefix of shared libraries.
     */
    public JarLibraryLoader(CodeSource codeSource, String libraryPath) {
        this.codeSource  = codeSource;
        this.libraryPath = libraryPath;
    }

    /**
     * Load a shared library, and optionally verify the jar signatures.
     *
     * @param name      Name of the library to load.
     * @param verify    Verify the jar file if signed.
     *
     * @return true if the library was successfully loaded.
     */
    public boolean load(String name, boolean verify) {

        File temporary = null;

        try {

            String location = codeSource.getLocation().getPath();

            if(location.contains(NESTED_DELIMITER)) {

                temporary = File.createTempFile("scrypt-temporary", ".jar");

                // Nested Jar File.

                List<String> strings = SPLITTER.splitToList(location);

                LOGGER.debug("Extracting nested jar file to {}.", temporary.getPath());

                ByteStreams.copy(getClass().getResourceAsStream(strings.get(NESTED_JAR_INDEX)),
                        new FileOutputStream(temporary));
                JarFile jar = new JarFile(temporary, verify);

                return loadLibrary(name, jar);

            }

            return loadLibrary(name, new JarFile(new File(codeSource.getLocation().toURI()), verify));

        } catch (Throwable e) {

            LOGGER.error("Error loading native library.", e);

            return false;

        } finally {

            if(temporary != null) {

                // Clean up the temporarily extracted copy of the jar file.

                boolean deleted = temporary.delete();

                if(!deleted) {
                    LOGGER.warn("Failed to delete temporary jar file {}.", temporary.getPath());
                }

            }

        }
    }

    private boolean loadLibrary(String name, JarFile jar) throws Exception {
        try {
            Platform platform = Platform.detect();
            for (String path : libCandidates(platform, name)) {
                JarEntry entry = jar.getJarEntry(path);
                if (entry == null) {
                    continue;
                }
                String ext = path.contains(".")
                            ? path.substring(path.lastIndexOf("."))
                            : "lib";

                File lib = extract(name, ext, jar.getInputStream(entry));
                System.load(lib.getAbsolutePath());
                boolean deleted = lib.delete();
                if(!deleted) {
                    LOGGER.warn("Failed to delete temporary library file {}.", lib.getPath());
                }
                return true;
            }
        } finally {
            jar.close();
        }
        return false;
    }

    /**
     * Extract a jar entry to a temp file.
     *
     * @param name  Name prefix for temp file.
     * @param is    Jar entry input stream.
     *
     * @return A temporary file.
     *
     * @throws IOException when an IO error occurs.
     */
    private static File extract(String name, String ext, InputStream is) throws IOException {
        byte[] buf = new byte[4096];
        int len;

        File lib = File.createTempFile(name, ext);
        FileOutputStream os = new FileOutputStream(lib);

        try {
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        } catch (IOException e) {
            lib.delete();
            throw e;
        } finally {
            os.close();
            is.close();
        }

        return lib;
    }

    /**
     * Generate a list of candidate libraries for the supplied library name and suitable
     * for the current platform.
     *
     * @param platform  Current platform.
     * @param name      Library name.
     *
     * @return List of potential library names.
     */
    private List<String> libCandidates(Platform platform, String name) {
        List<String> candidates = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();

        sb.append(libraryPath).append("/");
        sb.append(platform.arch).append("/");
        sb.append(platform.os).append("/");
        sb.append("lib").append(name);

        switch (platform.os) {
            case darwin:
                candidates.add(sb + ".dylib");
                candidates.add(sb + ".jnilib");
                break;
            case linux:
            case freebsd:
                candidates.add(sb + ".so");
                break;
            case windows:
            	candidates.add(sb + ".dll");
            	break;
        }

        return candidates;
    }
}
