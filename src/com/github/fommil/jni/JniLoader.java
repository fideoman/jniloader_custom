package com.github.fommil.jni;

import lombok.Cleanup;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.io.File.createTempFile;
import static java.nio.channels.Channels.newChannel;
import static java.util.logging.Level.*;
import static lombok.AccessLevel.PRIVATE;

/**
 * Simple (for the end user) native library loader.
 * </p>
 * The static method in this class enables developers to ship native libraries
 * in jar files which are extracted and loaded at runtime, making native loading
 * transparent for the end-user (as long as they are on a supported platform).
 * Native libraries can also be placed in {@code java.library.path} for slightly
 * faster startup times.
 */
@NoArgsConstructor(access = PRIVATE)
public final class JniLoader {

	private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(JniLoader.class.getName());

	public static final String JNI_EXTRACT_DIR_PROP = "com.github.fommil.jni.dir";

	private static final Set<String> loaded = new HashSet<String>();

	/**
	 * Attempts to load a native library from the {@code java.library.path} and (if
	 * that fails) will extract the named file from the classpath into a pre-defined
	 * directory ({@value #JNI_EXTRACT_DIR_PROP}, or - if not defined - a temporary
	 * directory is created) and load from there.
	 * <p/>
	 * Will stop on the first successful load of a native library.
	 *
	 * @param paths alternative relative path of the native library on either the
	 *              library path or classpath.
	 * @throws ExceptionInInitializerError if the input parameters are invalid or
	 *                                     all paths failed to load (making this
	 *                                     safe to use in static code blocks).
	 */
	public synchronized static void load(String... paths) {
		if (paths == null || paths.length == 0)
			throw new ExceptionInInitializerError("invalid parameters");

		for (String path : paths) {
			String key = new File(path).getName();
			if (loaded.contains(key)) {
				log.fine("already loaded " + path);
				return;
			}
		}

		String[] javaLibPath = System.getProperty("java.library.path").split(File.pathSeparator);
		for (String path : paths) {
			log.fine("JNI LIB = " + path);
			for (String libPath : javaLibPath) {
				File file = new File(libPath, path).getAbsoluteFile();
				log.fine("checking " + file);
				if (file.exists() && file.isFile() && liberalLoad(file, path))
					return;
			}
			File extracted = extract(path);
			if (extracted != null && liberalLoad(extracted, path))
				return;
		}

		throw new ExceptionInInitializerError("unable to load from " + Arrays.toString(paths));
	}

	// return true if the file was loaded, otherwise false.
	// side effect: files in the tmpdir that fail to load will be deleted
	private static boolean liberalLoad(File file, String name) {
		try {
			log.finest("attempting to load " + file);
			System.load(file.getAbsolutePath());
			log.info("successfully loaded " + file);
			loaded.add(name);
			return true;
		} catch (UnsatisfiedLinkError e) {
			log.log(FINE, "skipping load of " + file, e);
			String tmpdir = System.getProperty("java.io.tmpdir");
			if (tmpdir != null && tmpdir.trim().length() > 2 && file.getAbsolutePath().startsWith(tmpdir)) {
				log.log(FINE, "deleting " + file);
				try {
					file.delete();
				} catch (Exception e2) {
					log.fine("failed to delete " + file);
				}
			}
			return false;
		} catch (SecurityException e) {
			log.log(INFO, "skipping load of " + file, e);
			return false;
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static File extract(String path) {
		FileOutputStream fos = null;
		File file = null;
		try {
			long start = System.currentTimeMillis();
			URL url = JniLoader.class.getResource("/" + path);
			if (url == null)
				return null;

			log.fine("attempting to extract " + url);

			@Cleanup
			InputStream in = JniLoader.class.getResourceAsStream("/" + path);
			file = file(path);
			deleteOnExit(file);

			log.fine("extracting " + url + " to " + file.getAbsoluteFile());

			if (file.getAbsoluteFile().toString().toLowerCase().contains("javaswigwrapper")
					&& JniNamer.os().contains("win")) {
				// We'll extract associated files as well
				// This is VERY dirty.
				String[] winFiles = { "ampl-2.0.1.0.dll" };
				for (int i = 0; i < winFiles.length; i++) {
					if (JniLoader.class.getClassLoader().getResource(winFiles[i]) != null) {
						try {
							java.nio.file.Files.copy(JniLoader.class.getClassLoader().getResourceAsStream(winFiles[i]),
									java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")
											+ java.io.File.separator + winFiles[i]),
									java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							System.load(System.getProperty("java.io.tmpdir") + java.io.File.separator + winFiles[i]);
						} catch (java.io.IOException e) {
							log.info("Could not copy file " + winFiles[i] + " to Temp extraction point.");
						}
					}
				}
			}

			ReadableByteChannel src = newChannel(in);
			fos = new FileOutputStream(file);
			@Cleanup
			FileChannel dest = fos.getChannel();
			dest.transferFrom(src, 0, Long.MAX_VALUE);

			long end = System.currentTimeMillis();
			log.fine("extracted " + path + " in " + (end - start) + " millis");

			return file;
		} catch (Throwable e) {
			if (e instanceof SecurityException || e instanceof IOException) {
				log.log(INFO, "skipping extraction of " + path, e);
				return null;
			} else
				throw new ExceptionInInitializerError(e);
		} finally {
			try {
				fos.close();
			} catch (IOException e) {
				log.severe("Could not close Stream for file: " + file.getAbsolutePath());
			}
		}
	}

	private static File file(String path) throws IOException {
		String name = new File(path).getName();

		String dir = System.getProperty(JNI_EXTRACT_DIR_PROP);
		if (dir == null)
			return createTempFile("jniloader", name);

		File file = new File(dir, name);
		if (file.exists() && !file.isFile())
			throw new IllegalArgumentException(file.getAbsolutePath() + " is not a file.");
		if (!file.exists())
			file.createNewFile();
		return file;
	}

	/**
	 * Sadly, a wrapper for the File method of this name. Swallows security
	 * exceptions, which can erroneously appear (on OS X at least) despite the
	 * policy file saying otherwise and which are probably not fatal at any rate.
	 *
	 * @param file
	 * @see <a href=
	 *      "http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6997203">Java Bug
	 *      6997203</a>
	 */
	private static void deleteOnExit(File file) {
		try {
			file.deleteOnExit();
		} catch (Exception e1) {
			log.log(INFO, file.getAbsolutePath() + " delete denied, retrying - might be Java bug #6997203.");
			try {
				System.gc();
				Thread.yield();
				file.deleteOnExit();
			} catch (Exception e2) {
				log.log(WARNING, file.getAbsolutePath() + " delete denied a second time.", e2);
			}
		}
	}
}