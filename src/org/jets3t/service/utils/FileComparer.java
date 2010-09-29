/*
 * JetS3t : Java S3 Toolkit
 * Project hosted at http://bitbucket.org/jmurty/jets3t/
 *
 * Copyright 2006-2008 James Murty
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jets3t.service.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jets3t.service.Constants;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.S3ObjectsChunk;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.StorageObjectsChunk;
import org.jets3t.service.io.BytesProgressWatcher;
import org.jets3t.service.io.ProgressMonitoredInputStream;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.multithread.GetObjectHeadsEvent;
import org.jets3t.service.multithread.ListObjectsEvent;
import org.jets3t.service.multithread.S3ServiceEventAdaptor;
import org.jets3t.service.multithread.S3ServiceEventListener;
import org.jets3t.service.multithread.S3ServiceMulti;

/**
 * File comparison utility to compare files on the local computer with objects present in an S3
 * account and determine whether there are any differences. This utility contains methods to
 * build maps of the contents of the local file system or S3 account for comparison, and
 * <tt>buildDiscrepancyLists</tt> methods to find differences in these maps.
 * <p>
 * File comparisons are based primarily on MD5 hashes of the files' contents. If a local file does
 * not match an object in S3 with the same name, this utility determine which of the items is
 * newer by comparing the last modified dates.
 *
 * @author James Murty
 */
public class FileComparer {
    private static final Log log = LogFactory.getLog(FileComparer.class);

    private Jets3tProperties jets3tProperties = null;

    /**
     * Constructs the class.
     *
     * @param jets3tProperties
     * the object containing the properties that will be applied in this class.
     */
    public FileComparer(Jets3tProperties jets3tProperties) {
        this.jets3tProperties = jets3tProperties;
    }

    /**
     * @param jets3tProperties
     * the object containing the properties that will be applied in the instance.
     * @return
     * a FileComparer instance.
     */
    public static FileComparer getInstance(Jets3tProperties jets3tProperties) {
        return new FileComparer(jets3tProperties);
    }

    /**
     * @return
     * a FileComparer instance initialized with the default JetS3tProperties
     * object.
     */
    public static FileComparer getInstance() {
        return new FileComparer(
            Jets3tProperties.getInstance(Constants.JETS3T_PROPERTIES_FILENAME));
    }

    /**
     * If a <code>.jets3t-ignore</code> file is present in the given directory, the file is read
     * and all the paths contained in it are coverted to regular expression Pattern objects.
     * If the parent directory's list of patterns is provided, any relevant patterns are also
     * added to the ignore listing. Relevant parent patterns are those with a directory prefix
     * that matches the current directory, or with the wildcard depth pattern (*.*./).
     *
     * @param directory
     * a directory that may contain a <code>.jets3t-ignore</code> file. If this parameter is null
     * or is actually a file and not a directory, an empty list will be returned.
     * @param parentIgnorePatternList
     * a list of Patterns that were applied to the parent directory of the given directory. If this
     * parameter is null, no parent ignore patterns are applied.
     *
     * @return
     * a list of Pattern objects representing the paths in the ignore file. If there is no ignore
     * file, or if it has no contents, the list returned will be empty.
     */
    protected List<Pattern> buildIgnoreRegexpList(File directory, List<Pattern> parentIgnorePatternList) {
        List<Pattern> ignorePatternList = new ArrayList<Pattern>();

        // Add any applicable ignore patterns found in ancestor directories
        if (parentIgnorePatternList != null) {
            Iterator<Pattern> parentIgnorePatternIter = parentIgnorePatternList.iterator();
            while (parentIgnorePatternIter.hasNext()) {
                Pattern parentPattern = parentIgnorePatternIter.next();
                String parentIgnorePatternString = parentPattern.pattern();

                // If parent ignore pattern contains a slash, it is eligible for inclusion.
                int slashOffset = parentIgnorePatternString.indexOf(Constants.FILE_PATH_DELIM);
                if (slashOffset >= 0 && parentIgnorePatternString.length() > (slashOffset + 1)) { // Ensure there is at least 1 char after slash
                    // Chop pattern into header and tail around first slash character.
                    String patternHeader = parentIgnorePatternString.substring(0, slashOffset);
                    String patternTail = parentIgnorePatternString.substring(slashOffset + 1);

                    if (".*.*".equals(patternHeader)) {
                        // ** patterns are special and apply to any directory depth, so add both the
                        // pattern's tail to match in this directory, and the original pattern to match
                        // again in descendent directories.
                        ignorePatternList.add(Pattern.compile(patternTail));
                        ignorePatternList.add(parentPattern);
                    } else if (Pattern.compile(patternHeader).matcher(directory.getName()).matches()) {
                        // Adds pattern's tail section to ignore list for this directory, provided
                        // the pre-slash pattern matches the current directory's name.
                        ignorePatternList.add(Pattern.compile(patternTail));
                    }
                }
            }
        }

        if (directory == null || !directory.isDirectory()) {
            return ignorePatternList;
        }

        File jets3tIgnoreFile = new File(directory, Constants.JETS3T_IGNORE_FILENAME);
        if (jets3tIgnoreFile.exists() && jets3tIgnoreFile.canRead()) {
            if (log.isDebugEnabled()) {
                log.debug("Found ignore file: " + jets3tIgnoreFile.getPath());
            }
            try {
                String ignorePaths = ServiceUtils.readInputStreamToString(
                    new FileInputStream(jets3tIgnoreFile), null);
                StringTokenizer st = new StringTokenizer(ignorePaths.trim(), "\n");
                while (st.hasMoreTokens()) {
                    String ignorePath = st.nextToken();

                    // Convert path to RegExp.
                    String ignoreRegexp = ignorePath;
                    ignoreRegexp = ignoreRegexp.replaceAll("\\.", "\\\\.");
                    ignoreRegexp = ignoreRegexp.replaceAll("\\*", ".*");
                    ignoreRegexp = ignoreRegexp.replaceAll("\\?", ".");

                    Pattern pattern = Pattern.compile(ignoreRegexp);
                    if (log.isDebugEnabled()) {
                        log.debug("Ignore path '" + ignorePath + "' has become the regexp: "
                        + pattern.pattern());
                    }
                    ignorePatternList.add(pattern);

                    if (pattern.pattern().startsWith(".*.*/") && pattern.pattern().length() > 5) {
                        // **/ patterns are special and apply to any directory depth, including the current
                        // directory. So add the pattern's after-slash tail to match in this directory as well.
                        ignorePatternList.add(Pattern.compile(pattern.pattern().substring(5)));
                    }

                }
            } catch (IOException e) {
                if (log.isErrorEnabled()) {
                    log.error("Failed to read contents of ignore file '" + jets3tIgnoreFile.getPath()
                    + "'", e);
                }
            }
        }

        if (jets3tProperties.getBoolProperty("filecomparer.skip-upload-of-md5-files", false))
        {
            Pattern pattern = Pattern.compile(".*\\.md5");
            if (log.isDebugEnabled()) {
                log.debug("Skipping upload of pre-computed MD5 files with path '*.md5' using the regexp: "
                + pattern.pattern());
            }
            ignorePatternList.add(pattern);
        }

        return ignorePatternList;
    }

    /**
     * Determines whether a file should be ignored when building a file map. A file may be ignored
     * in two situations: 1) if it matches a regular expression pattern in the given list of
     * ignore patterns, or 2) if it is a symlink/alias and the JetS3tProperties setting
     * "filecomparer.skip-symlinks" is true.
     *
     * @param ignorePatternList
     * a list of Pattern objects representing the file names to ignore.
     * @param file
     * a file that will either be ignored or not, depending on whether it matches an ignore Pattern
     * or is a symlink/alias.
     *
     * @return
     * true if the file should be ignored, false otherwise.
     */
    protected boolean isIgnored(List<Pattern> ignorePatternList, File file) {
        if (jets3tProperties.getBoolProperty("filecomparer.skip-symlinks", false)) {
            /*
             * Check whether this file is actually a symlink/alias, and skip it if so.
             * Since Java IO libraries do not provide an official way to determine whether
             * a file is a symlink, we rely on a property of symlinks where the absolute
             * path to the symlink differs from the canonical path. This is hacky, but
             * mostly seems to work...
             */
            try {
                if (!file.getAbsolutePath().equals(file.getCanonicalPath())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Ignoring symlink "
                            + (file.isDirectory() ? "directory" : "file") + ": " + file.getName());
                    }
                    // Skip symlink.
                    return true;
                }
            } catch (IOException e) {
                log.warn("Unable to determine whether "
                    + (file.isDirectory() ? "directory" : "file")
                    + " '" + file.getAbsolutePath() + "' is a symlink", e);
            }
        }

        Iterator<Pattern> patternIter = ignorePatternList.iterator();
        while (patternIter.hasNext()) {
            Pattern pattern = patternIter.next();

            if (pattern.matcher(file.getName()).matches()) {
                if (log.isDebugEnabled()) {
                    log.debug("Ignoring " + (file.isDirectory() ? "directory" : "file")
                    + " matching pattern '" + pattern.pattern() + "': " + file.getName());
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Normalize string into "Normalization Form Canonical Decomposition" (NFD).
     *
     * References:
     * http://stackoverflow.com/questions/3610013
     * http://en.wikipedia.org/wiki/Unicode_equivalence
     *
     * @param str
     * @return string normalized into NFC form.
     */
    protected String normalizeUnicode(String str) {
        Normalizer.Form form = Normalizer.Form.NFD;
        if (!Normalizer.isNormalized(str, form)) {
            return Normalizer.normalize(str, form);
        }
        return str;
    }

    /**
     * Builds a File Map containing the given files. If any of the given files are actually
     * directories, the contents of the directory are included.
     * <p>
     * File keys are delimited with '/' characters.
     * <p>
     * Any file or directory matching a path in a <code>.jets3t-ignore</code> file will be ignored.
     *
     * @param files
     * the set of files/directories to include in the file map.
     * @param includeDirectories
     * If true all directories, including empty ones, will be included in the Map. These directories
     * will be mere place-holder objects with a trailing slash (/) character in the name and the
     * content type {@link Mimetypes#MIMETYPE_BINARY_OCTET_STREAM}.
     * If this variable is false directory objects will not be included in the Map, and it will not
     * be possible to store empty directories in S3.
     *
     * @return
     * a Map of file path keys to File objects.
     */
    public Map<String, File> buildFileMap(File[] files, boolean includeDirectories) {
        // Build map of files proposed for upload or download.
        Map<String, File> fileMap = new HashMap<String, File>();
        List<Pattern> ignorePatternList = null;
        List<Pattern> ignorePatternListForCurrentDir = null;

        for (int i = 0; i < files.length; i++) {
            File file = files[i];

            if (file.getParentFile() == null) {
                // For direct references to a file or dir, look for a .jets3t-ignore file
                // in the current directory - only do this once for the current dir.
                if (ignorePatternListForCurrentDir == null) {
                    ignorePatternListForCurrentDir = buildIgnoreRegexpList(new File("."), null);
                }
                ignorePatternList = ignorePatternListForCurrentDir;
            } else {
                ignorePatternList = buildIgnoreRegexpList(file.getParentFile(), null);
            }

            if (!isIgnored(ignorePatternList, file)) {
                if (!file.exists()) {
                    continue;
                }
                if (!file.isDirectory()) {
                    fileMap.put(normalizeUnicode(file.getName()), file);
                }
                if (file.isDirectory() && includeDirectories) {
                    String fileName = normalizeUnicode(file.getName() + Constants.FILE_PATH_DELIM);
                    fileMap.put(fileName, file);
                    buildFileMapImpl(file, fileName, fileMap, includeDirectories, ignorePatternList);
                }
            }
        }
        return fileMap;
    }

    /**
     * Builds a File Map containing all the files and directories inside the given root directory,
     * where the map's key for each file is the relative path to the file.
     * <p>
     * File keys are delimited with '/' characters.
     * <p>
     * Any file or directory matching a path in a <code>.jets3t-ignore</code> file will be ignored.
     *
     * @see #buildDiscrepancyLists(Map, Map)
     * @see #buildS3ObjectMap(S3Service, S3Bucket, String, boolean, S3ServiceEventListener)
     *
     * @param rootDirectory
     * The root directory containing the files/directories of interest. The root directory is <b>not</b>
     * included in the result map.
     * @param fileKeyPrefix
     * A prefix added to each file path key in the map, e.g. the name of the root directory the
     * files belong to. If provided, a '/' suffix is always added to the end of the prefix. If null
     * or empty, no prefix is used.
     * @param includeDirectories
     * If true all directories, including empty ones, will be included in the Map. These directories
     * will be mere place-holder objects with a trailing slash (/) character in the name and the
     * content type {@link Mimetypes#MIMETYPE_BINARY_OCTET_STREAM}.
     * If this variable is false directory objects will not be included in the Map, and it will not
     * be possible to store empty directories in S3.
     *
     * @return A Map of file path keys to File objects.
     */
    public Map<String, File> buildFileMap(File rootDirectory, String fileKeyPrefix,
        boolean includeDirectories)
    {
        Map<String, File> fileMap = new HashMap<String, File>();
        List<Pattern> ignorePatternList = buildIgnoreRegexpList(rootDirectory, null);

        if (!isIgnored(ignorePatternList, rootDirectory)) {
            if (fileKeyPrefix == null || fileKeyPrefix.length() == 0) {
                fileKeyPrefix = "";
            } else {
                if (!fileKeyPrefix.endsWith(Constants.FILE_PATH_DELIM)) {
                    fileKeyPrefix += Constants.FILE_PATH_DELIM;
                }
            }
            buildFileMapImpl(rootDirectory, fileKeyPrefix, fileMap, includeDirectories, ignorePatternList);
        }
        return fileMap;
    }

    /**
     * Recursively builds a File Map containing all the files and directories inside the given directory,
     * where the map's key for each file is the relative path to the file.
     * <p>
     * File keys are delimited with '/' characters.
     * <p>
     * Any file or directory matching a path in a <code>.jets3t-ignore</code> file will be ignored.
     *
     * @param directory
     * The directory containing the files/directories of interest. The directory is <b>not</b>
     * included in the result map.
     * @param fileKeyPrefix
     * A prefix added to each file path key in the map, e.g. the name of the root directory the
     * files belong to. This prefix <b>must</b> end with a '/' character.
     * @param fileMap
     * a map of path keys to File objects, that this method adds items to.
     * @param includeDirectories
     * If true all directories, including empty ones, will be included in the Map. These directories
     * will be mere place-holder objects with a trailing slash (/) character in the name and the
     * content type {@link Mimetypes#MIMETYPE_BINARY_OCTET_STREAM}.
     * If this variable is false directory objects will not be included in the Map, and it will not
     * be possible to store empty directories in S3.
     * @param parentIgnorePatternList
     * a list of Patterns that were applied to the parent directory of the given directory. This list
     * will be checked to see if any of the parent's patterns should apply to the current directory.
     * See {@link #buildIgnoreRegexpList(File, List)} for more information.
     * If this parameter is null, no parent ignore patterns are applied.
     */
    protected void buildFileMapImpl(File directory, String fileKeyPrefix,
        Map<String, File> fileMap, boolean includeDirectories, List<Pattern> parentIgnorePatternList)
    {
        List<Pattern> ignorePatternList = buildIgnoreRegexpList(directory, parentIgnorePatternList);

        File children[] = directory.listFiles();
        for (int i = 0; children != null && i < children.length; i++) {
            if (!isIgnored(ignorePatternList, children[i])) {
                String filePath = normalizeUnicode(fileKeyPrefix + children[i].getName());
                if (children[i].isDirectory() && includeDirectories) {
                    fileMap.put(filePath + Constants.FILE_PATH_DELIM, children[i]);
                } else if (!children[i].isDirectory()) {
                    fileMap.put(filePath, children[i]);
                }
                if (children[i].isDirectory()) {
                    buildFileMapImpl(
                        children[i], filePath + Constants.FILE_PATH_DELIM,
                        fileMap, includeDirectories, ignorePatternList);
                }
            }
        }
    }

    /**
     * Lists the objects in a bucket using a partitioning technique to divide
     * the object namespace into separate partitions that can be listed by
     * multiple simultaneous threads. This method divides the object namespace
     * using the given delimiter, traverses this space up to the specified
     * depth to identify prefix names for multiple "partitions", and
     * then lists the objects in each partition. It returns the complete list
     * of objects in the bucket path.
     * <p>
     * This partitioning technique will work best for buckets with many objects
     * that are divided into a number of virtual subdirectories of roughly equal
     * size.
     *
     * @param s3Service
     * the service object that will be used to perform listing requests.
     * @param bucketName
     * the name of the bucket whose contents will be listed.
     * @param targetPath
     * a root path within the bucket to be listed. If this parameter is null, all
     * the bucket's objects will be listed. Otherwise, only the objects below the
     * virtual path specified will be listed.
     * @param delimiter
     * the delimiter string used to identify virtual subdirectory partitions
     * in a bucket. If this parameter is null, or it has a value that is not
     * present in your object names, no partitioning will take place.
     * @param toDepth
     * the number of delimiter levels this method will traverse to identify
     * subdirectory partions. If this value is zero, no partitioning will take
     * place.
     *
     * @return
     * the list of objects under the target path in the bucket.
     *
     * @throws S3ServiceException
     */
    public StorageObject[] listObjectsThreaded(S3Service s3Service,
        final String bucketName, String targetPath, final String delimiter, int toDepth)
        throws ServiceException
    {
        final List<StorageObject> allObjects =
            Collections.synchronizedList(new ArrayList<StorageObject>());
        final List<String> lastCommonPrefixes =
            Collections.synchronizedList(new ArrayList<String>());
        final ServiceException s3ServiceExceptions[] = new ServiceException[1];

        /*
         * Create a S3ServiceMulti object with an event listener that responds to
         * ListObjectsEvent notifications and populates a complete object listing.
         */
        final S3ServiceMulti s3Multi = new S3ServiceMulti(s3Service, new S3ServiceEventAdaptor() {
            @Override
            public void s3ServiceEventPerformed(ListObjectsEvent event) {
                if (ListObjectsEvent.EVENT_IN_PROGRESS == event.getEventCode()) {
                    Iterator<S3ObjectsChunk> chunkIter = event.getChunkList().iterator();
                    while (chunkIter.hasNext()) {
                        S3ObjectsChunk chunk = chunkIter.next();

                        if (log.isDebugEnabled()) {
                            log.debug("Listed " + chunk.getObjects().length
                            + " objects and " + chunk.getCommonPrefixes().length
                            + " common prefixes in bucket '" + bucketName
                            + "' using prefix=" + chunk.getPrefix()
                            + ", delimiter=" + chunk.getDelimiter());
                        }

                        allObjects.addAll(Arrays.asList(chunk.getObjects()));
                        lastCommonPrefixes.addAll(Arrays.asList(chunk.getCommonPrefixes()));
                    }
                } else if (ListObjectsEvent.EVENT_ERROR == event.getEventCode()) {
                    s3ServiceExceptions[0] = new ServiceException(
                        "Failed to list all objects in bucket",
                        event.getErrorCause());
                }
            }
        });

        // The first listing partition we use as a starting point is the target path.
        String[] prefixesToList = new String[] {targetPath};
        int currentDepth = 0;

        while (currentDepth <= toDepth && prefixesToList.length > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Listing objects in '" + bucketName + "' using "
                + prefixesToList.length + " prefixes: "
                + Arrays.asList(prefixesToList));
            }

            // Initialize the variables that will be used, or populated, by the
            // multi-threaded listing.
            lastCommonPrefixes.clear();
            final String[] finalPrefixes = prefixesToList;
            final String finalDelimiter = (currentDepth < toDepth ? delimiter : null);

            /*
             * Perform a multi-threaded listing, where each prefix string
             * will be used as a unique partition to be listed in a separate thread.
             */
            (new Thread() {
                @Override
                public void run() {
                    s3Multi.listObjects(bucketName, finalPrefixes,
                        finalDelimiter, Constants.DEFAULT_OBJECT_LIST_CHUNK_SIZE);
                };
            }).run();
            // Throw any exceptions that occur inside the threads.
            if (s3ServiceExceptions[0] != null) {
                throw s3ServiceExceptions[0];
            }

            // We use the common prefix paths identified in the last listing
            // iteration, if any, to identify partitions for follow-up listings.
            prefixesToList = lastCommonPrefixes
                .toArray(new String[lastCommonPrefixes.size()]);

            currentDepth++;
        }

        return allObjects.toArray(new S3Object[allObjects.size()]);
    }


    /**
     * Lists the objects in a bucket using a partitioning technique to divide
     * the object namespace into separate partitions that can be listed by
     * multiple simultaneous threads. This method divides the object namespace
     * using the given delimiter, traverses this space up to the specified
     * depth to identify prefix names for multiple "partitions", and
     * then lists the objects in each partition. It returns the complete list
     * of objects in the bucket path.
     * <p>
     * This partitioning technique will work best for buckets with many objects
     * that are divided into a number of virtual subdirectories of roughly equal
     * size.
     * <p>
     * The delimiter and depth properties that define how this method will
     * partition the bucket's namespace are set in the jets3t.properties file
     * with the setting:
     * filecomparer.bucket-listing.&lt;bucketname>=&lt;delim>,&lt;depth><br>
     * For example: <code>filecomparer.bucket-listing.my-bucket=/,2</code>
     *
     * @param s3Service
     * the service object that will be used to perform listing requests.
     * @param bucketName
     * the name of the bucket whose contents will be listed.
     * @param targetPath
     * a root path within the bucket to be listed. If this parameter is null, all
     * the bucket's objects will be listed. Otherwise, only the objects below the
     * virtual path specified will be listed.
     *
     * @return
     * the list of objects under the target path in the bucket.
     *
     * @throws S3ServiceException
     */
    public StorageObject[] listObjectsThreaded(S3Service s3Service,
        final String bucketName, String targetPath) throws ServiceException
    {
        String delimiter = null;
        int toDepth = 0;

        // Find bucket-specific listing properties, if any.
        String bucketListingProperties = jets3tProperties.getStringProperty(
            "filecomparer.bucket-listing." + bucketName, null);
        if (bucketListingProperties != null) {
            String splits[] = bucketListingProperties.split(",");
            if (splits.length != 2) {
                throw new ServiceException(
                    "Invalid setting for bucket listing property "
                    + "filecomparer.bucket-listing." + bucketName + ": '" +
                    bucketListingProperties + "'");
            }
            delimiter = splits[0].trim();
            toDepth = Integer.parseInt(splits[1]);
        }

        return listObjectsThreaded(s3Service, bucketName, targetPath,
            delimiter, toDepth);
    }

    /**
     * Builds an S3 Object Map containing all the objects within the given target path,
     * where the map's key for each object is the relative path to the object.
     *
     * @see #buildDiscrepancyLists(Map, Map)
     * @see #buildFileMap(File, String, boolean)
     *
     * @param s3Service
     * @param bucket
     * @param targetPath
     * @param skipMetadata
     * @param s3ServiceEventListener
     * @return
     * mapping of keys/S3Objects
     * @throws S3ServiceException
     */
    public Map<String, StorageObject> buildS3ObjectMap(S3Service s3Service, S3Bucket bucket,
        String targetPath, boolean skipMetadata, S3ServiceEventListener s3ServiceEventListener)
        throws ServiceException
    {
        String prefix = (targetPath.length() > 0 ? targetPath : null);
        StorageObject[] s3ObjectsIncomplete = this.listObjectsThreaded(
            s3Service, bucket.getName(), prefix);
        return buildS3ObjectMap(s3Service, bucket, targetPath, s3ObjectsIncomplete,
            skipMetadata, s3ServiceEventListener);
    }


    /**
     * Builds an S3 Object Map containing a partial set of objects within the given target path,
     * where the map's key for each object is the relative path to the object.
     * <p>
     * If the method is asked to perform a complete listing, it will use the
     * {@link #listObjectsThreaded(S3Service, String, String)} method to list the objects
     * in the bucket, potentially taking advantage of any bucket name partitioning
     * settings you have applied.
     * <p>
     * If the method is asked to perform only a partial listing, no bucket name
     * partitioning will be applied.
     *
     * @see #buildDiscrepancyLists(Map, Map)
     * @see #buildFileMap(File, String, boolean)
     *
     * @param s3Service
     * @param bucket
     * @param targetPath
     * @param priorLastKey
     * the prior last key value returned by a prior invocation of this method, if any.
     * @param completeListing
     * if true, this method will perform a complete listing of an S3 target.
     * If false, the method will list a partial set of objects commencing from the
     * given prior last key.
     *
     * @return
     * an object containing a mapping of key names to S3Objects, and the prior last
     * key (if any) that should be used to perform follow-up method calls.
     * @throws S3ServiceException
     */
    public PartialObjectListing buildS3ObjectMapPartial(S3Service s3Service, S3Bucket bucket,
        String targetPath, String priorLastKey, boolean completeListing,
        boolean skipMetadata, S3ServiceEventListener s3ServiceEventListener)
        throws ServiceException
    {
        String prefix = (targetPath.length() > 0 ? targetPath : null);
        StorageObject[] objects = null;
        String resultPriorLastKey = null;
        if (completeListing) {
            objects = listObjectsThreaded(s3Service, bucket.getName(), prefix);
        } else {
            StorageObjectsChunk chunk = s3Service.listObjectsChunked(
                bucket.getName(), prefix, null, Constants.DEFAULT_OBJECT_LIST_CHUNK_SIZE,
                priorLastKey, completeListing);
            objects = chunk.getObjects();
            resultPriorLastKey = chunk.getPriorLastKey();
        }

        Map<String, StorageObject> objectsMap = buildS3ObjectMap(s3Service, bucket, targetPath,
            objects, skipMetadata, s3ServiceEventListener);
        return new PartialObjectListing(objectsMap, resultPriorLastKey);
    }

    /**
     * Builds an S3 Object Map containing all the given objects, by retrieving HEAD details about
     * all the objects and using {@link #populateS3ObjectMap(String, StorageObject[])}
     * to product an object/key map.
     *
     * @see #buildDiscrepancyLists(Map, Map)
     * @see #buildFileMap(File[], boolean)
     *
     * @param s3Service
     * @param bucket
     * @param targetPath
     * @param skipMetadata
     * @param s3ObjectsIncomplete
     * @return
     * mapping of keys/S3Objects
     * @throws S3ServiceException
     */
    public Map<String, StorageObject> buildS3ObjectMap(S3Service s3Service, S3Bucket bucket,
        String targetPath, StorageObject[] s3ObjectsIncomplete, boolean skipMetadata,
        S3ServiceEventListener s3ServiceEventListener)
        throws ServiceException
    {
        StorageObject[] s3Objects = null;

        if (skipMetadata) {
            s3Objects = s3ObjectsIncomplete;
        } else {
            // Retrieve the complete information about all objects listed via GetObjectsHeads.
            final List<StorageObject> s3ObjectsCompleteList =
                new ArrayList<StorageObject>(s3ObjectsIncomplete.length);
            final ServiceException s3ServiceExceptions[] = new ServiceException[1];
            S3ServiceMulti s3ServiceMulti = new S3ServiceMulti(s3Service, new S3ServiceEventAdaptor() {
                @Override
                public void s3ServiceEventPerformed(GetObjectHeadsEvent event) {
                    if (GetObjectHeadsEvent.EVENT_IN_PROGRESS == event.getEventCode()) {
                        S3Object[] finishedObjects = event.getCompletedObjects();
                        if (finishedObjects.length > 0) {
                            s3ObjectsCompleteList.addAll(Arrays.asList(finishedObjects));
                        }
                    } else if (GetObjectHeadsEvent.EVENT_ERROR == event.getEventCode()) {
                        s3ServiceExceptions[0] = new ServiceException(
                            "Failed to retrieve detailed information about all S3 objects",
                            event.getErrorCause());
                    }
                }
            });
            if (s3ServiceEventListener != null) {
                s3ServiceMulti.addServiceEventListener(s3ServiceEventListener);
            }
            s3ServiceMulti.getObjectsHeads(bucket, S3Object.cast(s3ObjectsIncomplete));
            if (s3ServiceExceptions[0] != null) {
                throw s3ServiceExceptions[0];
            }
            s3Objects = s3ObjectsCompleteList
                .toArray(new S3Object[s3ObjectsCompleteList.size()]);
        }

        return populateS3ObjectMap(targetPath, s3Objects);
    }

    /**
     * Builds a map of key/object pairs each object is associated with a key based on its location
     * in the S3 target path.
     *
     * @param targetPath
     * @param s3Objects
     * @return
     * a map of key/S3Object pairs.
     */
    public Map<String, StorageObject> populateS3ObjectMap(String targetPath, StorageObject[] s3Objects) {
        Map<String, StorageObject> map = new HashMap<String, StorageObject>();
        for (int i = 0; i < s3Objects.length; i++) {
            String relativeKey = s3Objects[i].getKey();
            if (targetPath.length() > 0) {
                relativeKey = relativeKey.substring(targetPath.length());
                int slashIndex = relativeKey.indexOf(Constants.FILE_PATH_DELIM);
                if (slashIndex == 0) {
                    relativeKey = relativeKey.substring(slashIndex + 1, relativeKey.length());
                } else {
                    // This object is the result of a prefix search, not an explicit directory.
                    // Base the relative key on the last full subdirectory in the
                    // target path if available...
                    slashIndex = targetPath.lastIndexOf(Constants.FILE_PATH_DELIM);
                    if (slashIndex >= 0) {
                        relativeKey = s3Objects[i].getKey().substring(slashIndex + 1);
                    }
                    // ...otherwise, use the full object key name.
                    else {
                        relativeKey = s3Objects[i].getKey();
                    }
                }
            }
            if (relativeKey.length() > 0) {
                map.put(normalizeUnicode(relativeKey), s3Objects[i]);
            }
        }
        return map;
    }

    /**
     * Compares the contents of a directory on the local file system with the contents of an
     * S3 resource. This comparison is performed on a map of files and a map of S3 objects previously
     * generated using other methods in this class.
     *
     * @param filesMap
     * a map of keys/Files built using the method {@link #buildFileMap(File, String, boolean)}
     * @param s3ObjectsMap
     * a map of keys/S3Objects built using the method
     * {@link #buildS3ObjectMap(S3Service, S3Bucket, String, boolean, S3ServiceEventListener)}
     * @return
     * an object containing the results of the file comparison.
     *
     * @throws NoSuchAlgorithmException
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ParseException
     */
    public FileComparerResults buildDiscrepancyLists(Map<String, File> filesMap,
        Map<String, StorageObject> s3ObjectsMap)
        throws NoSuchAlgorithmException, FileNotFoundException, IOException, ParseException
    {
        return buildDiscrepancyLists(filesMap, s3ObjectsMap, null);
    }

    /**
     * Compares the contents of a directory on the local file system with the contents of an
     * S3 resource. This comparison is performed on a map of files and a map of S3 objects previously
     * generated using other methods in this class.
     *
     * @param filesMap
     * a map of keys/Files built using the method {@link #buildFileMap(File, String, boolean)}
     * @param s3ObjectsMap
     * a map of keys/S3Objects built using the method
     * {@link #buildS3ObjectMap(S3Service, S3Bucket, String, boolean, S3ServiceEventListener)}
     * @param progressWatcher
     * watches the progress of file hash generation.
     * @return
     * an object containing the results of the file comparison.
     *
     * @throws NoSuchAlgorithmException
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ParseException
     */
    public FileComparerResults buildDiscrepancyLists(Map<String, File> filesMap,
        Map<String, StorageObject> s3ObjectsMap, BytesProgressWatcher progressWatcher)
        throws NoSuchAlgorithmException, FileNotFoundException, IOException, ParseException
    {
        Set<String> onlyOnServerKeys = new HashSet<String>();
        Set<String> updatedOnServerKeys = new HashSet<String>();
        Set<String> updatedOnClientKeys = new HashSet<String>();
        Set<String> onlyOnClientKeys = new HashSet<String>();
        Set<String> alreadySynchronisedKeys = new HashSet<String>();
        Set<String> alreadySynchronisedLocalPaths = new HashSet<String>();

        // Read property settings for file comparison.
        boolean useMd5Files = jets3tProperties
            .getBoolProperty("filecomparer.use-md5-files", false);
        boolean generateMd5Files = jets3tProperties
            .getBoolProperty("filecomparer.generate-md5-files", false);
        boolean assumeLocalLatestInMismatch = jets3tProperties
            .getBoolProperty("filecomparer.assume-local-latest-in-mismatch", false);
        String md5FilesRootDirectoryPath = jets3tProperties
            .getStringProperty("filecomparer.md5-files-root-dir", null);
        File md5FilesRootDirectory = null;
        if (md5FilesRootDirectoryPath != null) {
            md5FilesRootDirectory = new File(md5FilesRootDirectoryPath);
            if (!md5FilesRootDirectory.isDirectory()) {
                throw new FileNotFoundException(
                    "filecomparer.md5-files-root-dir path is not a directory: "
                    + md5FilesRootDirectoryPath);
            }
        }

        // Check files on server against local client files.
        Iterator<Map.Entry<String, StorageObject>> s3ObjectsMapIter = s3ObjectsMap.entrySet().iterator();
        while (s3ObjectsMapIter.hasNext()) {
            Map.Entry<String, StorageObject> entry = s3ObjectsMapIter.next();
            String keyPath = entry.getKey();
            StorageObject storageObject = entry.getValue();

            for (String localPath: splitFilePathIntoDirPaths(keyPath, storageObject.isDirectoryPlaceholder()))
            {
                // Check whether local file is already on server
                if (filesMap.containsKey(localPath)) {
                    // File has been backed up in the past, is it still up-to-date?
                    File file = filesMap.get(localPath);

                    if (file.isDirectory()) {
                        // We don't care about directory date changes, as long as it's present.
                        alreadySynchronisedKeys.add(keyPath);
                        alreadySynchronisedLocalPaths.add(localPath);
                    } else {
                        // Compare file hashes.
                        byte[] computedHash = null;

                        // Check whether a pre-computed MD5 hash file is available
                        File computedHashFile = (md5FilesRootDirectory != null
                            ? new File(md5FilesRootDirectory, localPath + ".md5")
                            : new File(file.getPath() + ".md5"));
                        if (useMd5Files
                            && computedHashFile.canRead()
                            && computedHashFile.lastModified() > file.lastModified())
                        {
                            BufferedReader br = null;
                            try {
                                // A pre-computed MD5 hash file is available, try to read this hash value
                                br = new BufferedReader(new FileReader(computedHashFile));
                                computedHash = ServiceUtils.fromHex(br.readLine().split("\\s")[0]);
                            } catch (Exception e) {
                                if (log.isWarnEnabled()) {
                                    log.warn("Unable to read hash from computed MD5 file", e);
                                }
                            } finally {
                                if (br != null) {
                                    br.close();
                                }
                            }
                        }

                        if (computedHash == null) {
                            // A pre-computed hash file was not available, or could not be read.
                            // Calculate the hash value anew.
                            InputStream hashInputStream = null;
                            if (progressWatcher != null) {
                                hashInputStream = new ProgressMonitoredInputStream( // Report on MD5 hash progress.
                                    new FileInputStream(file), progressWatcher);
                            } else {
                                hashInputStream = new FileInputStream(file);
                            }
                            computedHash = ServiceUtils.computeMD5Hash(hashInputStream);
                        }

                        String fileHashAsBase64 = ServiceUtils.toBase64(computedHash);

                        if (generateMd5Files && !file.getName().endsWith(".md5") &&
                            (!computedHashFile.exists()
                            || computedHashFile.lastModified() < file.lastModified()))
                        {
                            // Create parent directory for new hash file if necessary
                            File parentDir = computedHashFile.getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs();
                            }

                            // Create or update a pre-computed MD5 hash file.
                            FileWriter fw = null;
                            try {
                                fw = new FileWriter(computedHashFile);
                                fw.write(ServiceUtils.toHex(computedHash));
                            } catch (Exception e) {
                                if (log.isWarnEnabled()) {
                                    log.warn("Unable to write computed MD5 hash to a file", e);
                                }
                            } finally {
                                if (fw != null) {
                                    fw.close();
                                }
                            }
                        }

                        // Get the S3 object's Base64 hash.
                        String objectHash = null;
                        if (storageObject.containsMetadata(S3Object.METADATA_HEADER_ORIGINAL_HASH_MD5)) {
                            // Use the object's *original* hash, as it is an encoded version of a local file.
                            objectHash = (String) storageObject.getMetadata(
                                S3Object.METADATA_HEADER_ORIGINAL_HASH_MD5);
                            if (log.isDebugEnabled()) {
                                log.debug("Object in S3 is encoded, using the object's original hash value for: "
                                + storageObject.getKey());
                            }
                        } else {
                            // The object wasn't altered when uploaded, so use its current hash.
                            objectHash = storageObject.getMd5HashAsBase64();
                        }

                        if (fileHashAsBase64.equals(objectHash)) {
                            // Hashes match so file is already synchronised.
                            alreadySynchronisedKeys.add(keyPath);
                            alreadySynchronisedLocalPaths.add(localPath);
                        } else {
                            // File is out-of-synch. Check which version has the latest date.
                            Date s3ObjectLastModified = null;
                            String metadataLocalFileDate = (String) storageObject.getMetadata(
                                Constants.METADATA_JETS3T_LOCAL_FILE_DATE);

                            if (metadataLocalFileDate == null) {
                                // This is risky as local file times and S3 times don't match!
                                if (!assumeLocalLatestInMismatch && log.isWarnEnabled()) {
                                    log.warn("Using S3 last modified date as file date. This is not reliable "
                                    + "as the time according to S3 can differ from your local system time. "
                                    + "Please use the metadata item "
                                    + Constants.METADATA_JETS3T_LOCAL_FILE_DATE);
                                }
                                s3ObjectLastModified = storageObject.getLastModifiedDate();
                            } else {
                                s3ObjectLastModified = ServiceUtils
                                    .parseIso8601Date(metadataLocalFileDate);
                            }
                            if (s3ObjectLastModified.getTime() > file.lastModified()) {
                                updatedOnServerKeys.add(keyPath);
                            } else if (s3ObjectLastModified.getTime() < file.lastModified()) {
                                updatedOnClientKeys.add(keyPath);
                            } else {
                                // Local file date and S3 object date values match exactly, yet the
                                // local file has a different hash. This shouldn't ever happen, but
                                // sometimes does with Excel files.
                                if (assumeLocalLatestInMismatch) {
                                    if (log.isWarnEnabled()) {
                                        log.warn("Backed-up S3Object " + storageObject.getKey()
                                        + " and local file " + file.getName()
                                        + " have the same date but different hash values. "
                                        + "Assuming local file is the latest version.");
                                    }
                                    updatedOnClientKeys.add(keyPath);
                                } else {
                                    throw new IOException("Backed-up S3Object " + storageObject.getKey()
                                        + " and local file " + file.getName()
                                        + " have the same date but different hash values. "
                                        + "This shouldn't happen!");
                                }

                            }
                        }
                    }
                } else {
                    // File is not in local file system, so it's only on the S3
                    // server.
                    onlyOnServerKeys.add(keyPath);
                }
            }
        }

        // Any local files not already put into another list only exist locally.
        onlyOnClientKeys.addAll(filesMap.keySet());
        onlyOnClientKeys.removeAll(updatedOnClientKeys);
        onlyOnClientKeys.removeAll(updatedOnServerKeys);
        onlyOnClientKeys.removeAll(alreadySynchronisedKeys);
        onlyOnClientKeys.removeAll(alreadySynchronisedLocalPaths);

        return new FileComparerResults(onlyOnServerKeys, updatedOnServerKeys, updatedOnClientKeys,
            onlyOnClientKeys, alreadySynchronisedKeys, alreadySynchronisedLocalPaths);
    }

    private Set<String> splitFilePathIntoDirPaths(String path, boolean isDirectoryPlaceholder) {
        Set<String> dirPathsSet = new HashSet<String>();
        String[] pathComponents = path.split(Constants.FILE_PATH_DELIM);
        String myPath = "";
        for (int i = 0; i < pathComponents.length; i++) {
            String pathComponent = pathComponents[i];
            myPath = myPath + pathComponent;
            if (i < pathComponents.length - 1 || isDirectoryPlaceholder) {
                myPath += Constants.FILE_PATH_DELIM;
            }
            dirPathsSet.add(myPath);
        }
        return dirPathsSet;
    }

    public class PartialObjectListing {
        private Map<String, StorageObject> objectsMap = null;
        private String priorLastKey = null;

        public PartialObjectListing(Map<String, StorageObject> objectsMap, String priorLastKey) {
            this.objectsMap = objectsMap;
            this.priorLastKey = priorLastKey;
        }

        public Map<String, StorageObject> getObjectsMap() {
            return objectsMap;
        }

        public String getPriorLastKey() {
            return priorLastKey;
        }
    }

}
