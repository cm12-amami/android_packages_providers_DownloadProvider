/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static com.android.providers.downloads.Constants.TAG;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemClock;
import android.provider.Downloads;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some helper functions for the download manager
 */
public class Helpers {
    public static Random sRandom = new Random(SystemClock.uptimeMillis());

    /** Regex used to parse content-disposition headers */
    private static final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile("attachment;\\s*filename\\s*=\\s*\"([^\"]*)\"");

    private static final Object sUniqueLock = new Object();

    private Helpers() {
    }

    /*
     * Parse the Content-Disposition HTTP Header. The format of the header
     * is defined here: http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html
     * This header provides a filename for content that is going to be
     * downloaded to the file system. We only support the attachment type.
     */
    private static String parseContentDisposition(String contentDisposition) {
        try {
            Matcher m = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IllegalStateException ex) {
             // This function is defined as returning null when it can't parse the header
        }
        return null;
    }

    /**
     * Creates a filename (where the file should be saved) from info about a download.
     * This file will be touched to reserve it.
     */
    static String generateSaveFile(Context context, String url, String hint,
            String contentDisposition, String contentLocation, String mimeType, int destination)
            throws IOException {

        final File parent;
        final File[] parentTest;
        String name = null;

        if (destination == Downloads.Impl.DESTINATION_FILE_URI) {
            final File file = new File(Uri.parse(hint).getPath());
            parent = file.getParentFile().getAbsoluteFile();
            parentTest = new File[] { parent };
            name = file.getName();
        } else {
            parent = getRunningDestinationDirectory(context, destination);
            parentTest = new File[] {
                    parent,
                    getSuccessDestinationDirectory(context, destination)
            };
            name = chooseFilename(url, hint, contentDisposition, contentLocation);
        }

        // Ensure target directories are ready
        for (File test : parentTest) {
            if (!(test.isDirectory() || test.mkdirs())) {
                throw new IOException("Failed to create parent for " + test);
            }
        }

        if (DownloadDrmHelper.isDrmConvertNeeded(mimeType)) {
            name = DownloadDrmHelper.modifyDrmFwLockFileExtension(name);
        }

        final String prefix;
        final String suffix;
        final int dotIndex = name.lastIndexOf('.');
        final boolean missingExtension = dotIndex < 0;
        if (destination == Downloads.Impl.DESTINATION_FILE_URI) {
            // Destination is explicitly set - do not change the extension
            if (missingExtension) {
                prefix = name;
                suffix = "";
            } else {
                prefix = name.substring(0, dotIndex);
                suffix = name.substring(dotIndex);
            }
        } else {
            // Split filename between base and extension
            // Add an extension if filename does not have one
            if (missingExtension) {
                prefix = name;
                suffix = chooseExtensionFromMimeType(mimeType, true);
            } else {
                prefix = name.substring(0, dotIndex);
                suffix = chooseExtensionFromFilename(mimeType, destination, name, dotIndex);
            }
        }

        synchronized (sUniqueLock) {
            name = generateAvailableFilenameLocked(parentTest, prefix, suffix);

            // Claim this filename inside lock to prevent other threads from
            // clobbering us. We're not paranoid enough to use O_EXCL.
            final File file = new File(parent, name);
            file.createNewFile();
            return file.getAbsolutePath();
        }
    }

    private static String chooseFilename(String url, String hint, String contentDisposition,
            String contentLocation) {
        String filename = null;

        // First, try to use the hint from the application, if there's one
        if (filename == null && hint != null && !hint.endsWith("/")) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "getting filename from hint");
            }
            int index = hint.lastIndexOf('/') + 1;
            if (index > 0) {
                filename = hint.substring(index);
            } else {
                filename = hint;
            }
        }

        // If we couldn't do anything with the hint, move toward the content disposition
        if (filename == null && contentDisposition != null) {
            filename = parseContentDisposition(contentDisposition);
            if (filename != null) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "getting filename from content-disposition");
                }
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
            }
        }

        // If we still have nothing at this point, try the content location
        if (filename == null && contentLocation != null) {
            String decodedContentLocation = Uri.decode(contentLocation);
            if (decodedContentLocation != null
                    && !decodedContentLocation.endsWith("/")
                    && decodedContentLocation.indexOf('?') < 0) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "getting filename from content-location");
                }
                int index = decodedContentLocation.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = decodedContentLocation.substring(index);
                } else {
                    filename = decodedContentLocation;
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null
                    && !decodedUrl.endsWith("/") && decodedUrl.indexOf('?') < 0) {
                int index = decodedUrl.lastIndexOf('/') + 1;
                if (index > 0) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "getting filename from uri");
                    }
                    filename = decodedUrl.substring(index);
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "using default filename");
            }
            filename = Constants.DEFAULT_DL_FILENAME;
        }

        // The VFAT file system is assumed as target for downloads.
        // Replace invalid characters according to the specifications of VFAT.
        filename = FileUtils.buildValidFatFilename(filename);

        return filename;
    }

    private static String chooseExtensionFromMimeType(String mimeType, boolean useDefaults) {
        String extension = null;
        if (mimeType != null) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "adding extension from type");
                }
                extension = "." + extension;
            } else {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "couldn't find extension for " + mimeType);
                }
            }
        }
        if (extension == null) {
            if (mimeType != null && mimeType.toLowerCase().startsWith("text/")) {
                if (mimeType.equalsIgnoreCase("text/html")) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "adding default html extension");
                    }
                    extension = Constants.DEFAULT_DL_HTML_EXTENSION;
                } else if (useDefaults) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "adding default text extension");
                    }
                    extension = Constants.DEFAULT_DL_TEXT_EXTENSION;
                }
            } else if (useDefaults) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "adding default binary extension");
                }
                extension = Constants.DEFAULT_DL_BINARY_EXTENSION;
            }
        }
        return extension;
    }

    private static String chooseExtensionFromFilename(String mimeType, int destination,
            String filename, int lastDotIndex) {
        String extension = null;
        if (mimeType != null) {
            // Compare the last segment of the extension against the mime type.
            // If there's a mismatch, discard the entire extension.
            String typeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    filename.substring(lastDotIndex + 1));
            if (typeFromExt == null || !typeFromExt.equalsIgnoreCase(mimeType)) {
                extension = chooseExtensionFromMimeType(mimeType, false);
                if (extension != null) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "substituting extension from type");
                    }
                } else {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "couldn't find extension for " + mimeType);
                    }
                }
            }
        }
        if (extension == null) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "keeping extension");
            }
            extension = filename.substring(lastDotIndex);
        }
        return extension;
    }

    private static boolean isFilenameAvailableLocked(File[] parents, String name) {
        if (Constants.RECOVERY_DIRECTORY.equalsIgnoreCase(name)) return false;

        for (File parent : parents) {
            if (new File(parent, name).exists()) {
                return false;
            }
        }

        return true;
    }

    private static String generateAvailableFilenameLocked(
            File[] parents, String prefix, String suffix) throws IOException {
        String name = prefix + suffix;
        if (isFilenameAvailableLocked(parents, name)) {
            return name;
        }

        /*
        * This number is used to generate partially randomized filenames to avoid
        * collisions.
        * It starts at 1.
        * The next 9 iterations increment it by 1 at a time (up to 10).
        * The next 9 iterations increment it by 1 to 10 (random) at a time.
        * The next 9 iterations increment it by 1 to 100 (random) at a time.
        * ... Up to the point where it increases by 100000000 at a time.
        * (the maximum value that can be reached is 1000000000)
        * As soon as a number is reached that generates a filename that doesn't exist,
        *     that filename is used.
        * If the filename coming in is [base].[ext], the generated filenames are
        *     [base]-[sequence].[ext].
        */
        int sequence = 1;
        for (int magnitude = 1; magnitude < 1000000000; magnitude *= 10) {
            for (int iteration = 0; iteration < 9; ++iteration) {
                name = prefix + Constants.FILENAME_SEQUENCE_SEPARATOR + sequence + suffix;
                if (isFilenameAvailableLocked(parents, name)) {
                    return name;
                }
                sequence += sRandom.nextInt(magnitude) + 1;
            }
        }

        throw new IOException("Failed to generate an available filename");
    }

    /**
     * Checks whether the filename looks legitimate for security purposes. This
     * prevents us from opening files that aren't actually downloads.
     */
    static boolean isFilenameValid(Context context, File file) {
        final File[] whitelist;
        try {
            whitelist = new File[] {
                    context.getFilesDir().getCanonicalFile(),
                    context.getCacheDir().getCanonicalFile(),
                    Environment.getDownloadCacheDirectory().getCanonicalFile(),
                    Environment.getExternalStorageDirectory().getCanonicalFile(),
            };
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve canonical path: " + e);
            return false;
        }

        for (File testDir : whitelist) {
            if (FileUtils.contains(testDir, file)) {
                return true;
            }
        }

        return false;
    }

    public static File getRunningDestinationDirectory(Context context, int destination)
            throws IOException {
        return getDestinationDirectory(context, destination, true);
    }

    public static File getSuccessDestinationDirectory(Context context, int destination)
            throws IOException {
        return getDestinationDirectory(context, destination, false);
    }

    private static File getDestinationDirectory(Context context, int destination, boolean running)
            throws IOException {
        switch (destination) {
            case Downloads.Impl.DESTINATION_CACHE_PARTITION:
            case Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE:
            case Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING:
                if (running) {
                    return context.getFilesDir();
                } else {
                    return context.getCacheDir();
                }

            case Downloads.Impl.DESTINATION_SYSTEMCACHE_PARTITION:
                if (running) {
                    return new File(Environment.getDownloadCacheDirectory(),
                            Constants.DIRECTORY_CACHE_RUNNING);
                } else {
                    return Environment.getDownloadCacheDirectory();
                }

            case Downloads.Impl.DESTINATION_EXTERNAL:
                final File target = new File(
                        Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
                if (!target.isDirectory() && target.mkdirs()) {
                    throw new IOException("unable to create external downloads directory");
                }
                return target;

            default:
                throw new IllegalStateException("unexpected destination: " + destination);
        }
    }
}
