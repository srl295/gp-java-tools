/*  
 * Copyright IBM Corp. 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.g11n.pipeline.maven;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import com.ibm.g11n.pipeline.client.BundleData;
import com.ibm.g11n.pipeline.client.NewBundleData;
import com.ibm.g11n.pipeline.client.NewResourceEntryData;
import com.ibm.g11n.pipeline.client.ServiceClient;
import com.ibm.g11n.pipeline.client.ServiceException;
import com.ibm.g11n.pipeline.resfilter.Bundle;
import com.ibm.g11n.pipeline.resfilter.ResourceFilter;
import com.ibm.g11n.pipeline.resfilter.ResourceFilterFactory;
import com.ibm.g11n.pipeline.resfilter.ResourceString;

/**
 * Pushes string resource bundle data to an instance of Globalization
 * Pipeline service for translation.
 * 
 * @author Yoshito Umaoka
 */
@Mojo(name = "upload")
public class GPUploadMojo extends GPBaseMojo {
    /* (non-Javadoc)
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().debug("Entering GPUploadMojo#execute()");

        ServiceClient client = getServiceClient();

        try {
            Set<String> bundleIds = client.getBundleIds();
            List<BundleSet> bundleSets = getBundleSets();

            for (BundleSet bundleSet : bundleSets) {
                String srcLang = bundleSet.getSourceLanguage();
                Set<String> tgtLangs = resolveTargetLanguages(bundleSet);
                List<SourceBundleFile> bundleFiles = getSourceBundleFiles(bundleSet);

                // Process each bundle
                for (SourceBundleFile bf : bundleFiles) {
                    getLog().info(bf.getType() + " : " + bf.getBundleId() + " : " + bf.getFile().getAbsolutePath());

                    // Checks if the bundle already exists
                    String bundleId = bf.getBundleId();
                    boolean createNew = false;
                    if (bundleIds.contains(bundleId)) {
                        getLog().info("Found bundle:" + bundleId);
                        // Checks if the source language matches.
                        BundleData bundle = client.getBundleInfo(bundleId);
                        if (!srcLang.equals(bundle.getSourceLanguage())) {
                            throw new MojoFailureException("The source language in bundle:"
                                    + bundleId + "(" + bundle.getSourceLanguage()
                                    + ") does not match the specified language("
                                    + srcLang + ").");
                        }
                    } else {
                        getLog().info("bundle:" + bundleId + " does not exist, creating a new bundle.");
                        createNew = true;
                    }

                    // Parse the resource bundle file
                    ResourceFilter filter = ResourceFilterFactory.get(bf.getType());
                    Map<String, NewResourceEntryData> resEntries = new HashMap<>();

                    try (FileInputStream fis = new FileInputStream(bf.getFile())) {
                        Bundle resBundle = filter.parse(fis);

                        if (createNew) {
                            NewBundleData newBundleData = new NewBundleData(srcLang);
                            // set target languages
                            if (!tgtLangs.isEmpty()) {
                                newBundleData.setTargetLanguages(new TreeSet<String>(tgtLangs));
                            }
                            // set bundle notes
                            newBundleData.setNotes(resBundle.getNotes());
                            client.createBundle(bundleId, newBundleData);
                            getLog().info("Created bundle: " + bundleId);
                        }
                        Collection<ResourceString> resStrings = resBundle.getResourceStrings();
                        for (ResourceString resString : resStrings) {
                            NewResourceEntryData resEntryData = new NewResourceEntryData(resString.getValue());
                            int seqNum = resString.getSequenceNumber();
                            if (seqNum >= 0) {
                                resEntryData.setSequenceNumber(Integer.valueOf(seqNum));
                            }
                            // set resource string notes
                            resEntryData.setNotes(resString.getNotes());
                            resEntries.put(resString.getKey(), resEntryData);
                        }
                    } catch (IOException e) {
                        throw new MojoFailureException("Failed to read the resoruce data from "
                                + bf.getFile().getAbsolutePath() + ": " + e.getMessage(), e);
                    }

                    if (resEntries.isEmpty()) {
                        getLog().info("No resource entries in " + bf.getFile().getAbsolutePath());
                    } else {
                        // Upload the resource entries
                        client.uploadResourceEntries(bundleId, srcLang, resEntries);
                        getLog().info("Uploaded source language(" + srcLang
                                + ") resource entries(" + resEntries.size() + ") to bundle: " + bundleId);
                    }
                }
            }
        } catch (ServiceException e) {
            throw new MojoFailureException("Globalization Pipeline service error", e);
        }
    }
}
