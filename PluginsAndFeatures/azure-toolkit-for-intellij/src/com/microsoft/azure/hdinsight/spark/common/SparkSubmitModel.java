/**
 * Copyright (c) Microsoft Corporation
 * <p/>
 * All rights reserved.
 * <p/>
 * MIT License
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.hdinsight.spark.common;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.impl.artifacts.ArtifactUtil;
import com.intellij.packaging.impl.compiler.ArtifactCompileScope;
import com.intellij.packaging.impl.compiler.ArtifactsWorkspaceSettings;
import com.microsoft.azure.hdinsight.common.ClusterManagerEx;
import com.microsoft.azure.hdinsight.common.HDInsightUtil;
import com.microsoft.azure.hdinsight.common.JobStatusManager;
import com.microsoft.azure.hdinsight.sdk.cluster.EmulatorClusterDetail;
import com.microsoft.azure.hdinsight.sdk.cluster.IClusterDetail;
import com.microsoft.azure.hdinsight.sdk.common.AuthenticationException;
import com.microsoft.azure.hdinsight.sdk.common.HDIException;
import com.microsoft.azure.hdinsight.sdk.common.HttpResponse;
import com.microsoft.azure.hdinsight.sdk.common.NotSupportExecption;
import com.microsoft.azure.hdinsight.spark.uihelper.InteractiveTableModel;
import com.microsoft.azuretools.azurecommons.helpers.NotNull;
import com.microsoft.azuretools.azurecommons.helpers.StringHelper;
import com.microsoft.azuretools.telemetry.AppInsightsClient;
import com.microsoft.intellij.hdinsight.messages.HDInsightBundle;
import com.microsoft.tooling.msservices.components.DefaultLoader;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.JDomWriter;
import org.apache.commons.lang.StringUtils;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jdom.Text;
import rx.Single;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SparkSubmitModel {

    private static final String[] columns = {"Key", "Value", ""};
    private  static final String SparkYarnLogUrlFormat = "%s/yarnui/hn/cluster/app/%s";

    private static final String SUBMISSION_CONTENT_NAME = "spark_submission";
    private static final String SUBMISSION_ATTRIBUTE_CLUSTER_NAME = "cluster_name";
    private static final String SUBMISSION_ATTRIBUTE_SELECTED_CLUSTER = "selected_cluster";
    private static final String SUBMISSION_ATTRIBUTE_IS_LOCAL_ARTIFACT = "is_local_artifact";
    private static final String SUBMISSION_ATTRIBUTE_ARTIFACT_NAME = "artifact_name";
    private static final String SUBMISSION_ATTRIBUTE_LOCAL_ARTIFACT_PATH = "local_artifact_path";
    private static final String SUBMISSION_ATTRIBUTE_FILE_PATH = "file_path";
    private static final String SUBMISSION_ATTRIBUTE_CLASSNAME = "classname";
    private static final String SUBMISSION_CONTENT_JOB_CONF = "job_conf";
    private static final String SUBMISSION_CONTENT_COMMAND_LINE_ARGS = "cmd_line_args";
    private static final String SUBMISSION_CONTENT_REF_JARS = "ref_jars";
    private static final String SUBMISSION_CONTENT_REF_FILES = "ref_files";
    private static final String SUBMISSION_CONTENT_SSH_CERT= "ssh_cert";
    private static final String SUBMISSION_ATTRIBUTE_SSH_CERT_AUTHTYPE_NAME= "auth_type";
    private static final String SUBMISSION_ATTRIBUTE_SSH_CERT_USER_NAME= "user";
    private static final String SUBMISSION_ATTRIBUTE_SSH_CERT_PRIVATE_KEYPATH_NAME= "private_key_path";

    private static Map<Project, SparkSubmissionParameter> submissionParameterMap = new HashMap<>();

    private static Map<Project, SparkSubmitAdvancedConfigModel> submissionAdvancedConfigModelMap = new HashMap<>();

    private final Project project;

    private List<IClusterDetail> cachedClusterDetails;
    private Map<String, IClusterDetail> mapClusterNameToClusterDetail = new HashMap<>();
    private Map<String, Artifact> artifactHashMap = new HashMap<>();

    private SparkSubmissionParameter submissionParameter;

    private SparkSubmitAdvancedConfigModel advancedConfigModel;

    private DefaultComboBoxModel<String> clusterComboBoxModel;

    private DefaultComboBoxModel<String> artifactComboBoxModel;

    private SubmissionTableModel tableModel = new SubmissionTableModel(columns);

    private final Map<String, String> postEventProperty = new HashMap<>();


    public SparkSubmitModel(@NotNull Project project) {
        this(project, submissionParameterMap.get(project));
    }

    public SparkSubmitModel(@NotNull Project project, @NotNull SparkSubmissionParameter submissionParameter) {
        this.cachedClusterDetails = new ArrayList();
        this.project = project;

        this.clusterComboBoxModel = new DefaultComboBoxModel<>();
        this.artifactComboBoxModel = new DefaultComboBoxModel<>();
        this.submissionParameter = submissionParameter;
        this.advancedConfigModel = submissionAdvancedConfigModelMap.get(project);

        final List<Artifact> artifacts = ArtifactUtil.getArtifactWithOutputPaths(project);

        for (Artifact artifact : artifacts) {
            artifactHashMap.put(artifact.getName(), artifact);
            artifactComboBoxModel.addElement(artifact.getName());
            if (artifactComboBoxModel.getSize() == 0) {
                artifactComboBoxModel.setSelectedItem(artifact.getName());
            }
        }

        int index = submissionParameter != null ? artifactComboBoxModel.getIndexOf(submissionParameter.getArtifactName()) : -1;
        if (index != -1) {
            artifactComboBoxModel.setSelectedItem(submissionParameter.getArtifactName());
        }

        initializeTableModel(tableModel);
    }

    public SparkSubmissionParameter getSubmissionParameter() {
        return submissionParameter;
    }

    public void setSubmissionParameters(SparkSubmissionParameter submissionParameters){
        this.submissionParameter = submissionParameters;
        submissionParameterMap.put(project, submissionParameter);
    }

    public SparkSubmitAdvancedConfigModel getAdvancedConfigModel() { return advancedConfigModel; }

    public void setAdvancedConfigModel(SparkSubmitAdvancedConfigModel advancedConfigModel) {
        this.advancedConfigModel = advancedConfigModel;
        submissionAdvancedConfigModelMap.put(project, this.advancedConfigModel);
    }

    @NotNull
    public IClusterDetail getSelectedClusterDetail() {
        return mapClusterNameToClusterDetail.get((String) clusterComboBoxModel.getSelectedItem());
    }

    public DefaultComboBoxModel getClusterComboBoxModel() {
        return clusterComboBoxModel;
    }

    public DefaultComboBoxModel getArtifactComboBoxModel() {
        return artifactComboBoxModel;
    }

    public boolean isLocalArtifact() {
        return submissionParameter.isLocalArtifact();
    }

    public Project getProject() {
        return project;
    }

    public InteractiveTableModel getTableModel() {
        return tableModel;
    }

    public void setClusterComboBoxModel(List<IClusterDetail> cachedClusterDetails) {
        this.cachedClusterDetails = cachedClusterDetails;

        clusterComboBoxModel.removeAllElements();
        mapClusterNameToClusterDetail.clear();

        for (IClusterDetail clusterDetail : cachedClusterDetails) {
            String title = getCluserTitle(clusterDetail);
            mapClusterNameToClusterDetail.put(title, clusterDetail);
            clusterComboBoxModel.addElement(title);
        }

        int index = -1;
        if(submissionParameter != null) {
            String title = getCluserTitle(submissionParameter.getClusterName());
            index = clusterComboBoxModel.getIndexOf(title);
            if (index != -1) {
                clusterComboBoxModel.setSelectedItem(getCluserTitle(submissionParameter.getClusterName()));
            }
        }
    }

    public void action(@NotNull SparkSubmissionParameter submissionParameter) {
        HDInsightUtil.getJobStatusManager(project).setJobRunningState(true);

        setSubmissionParameters(submissionParameter);

        postEventAction();

        if (isLocalArtifact()) {
            submit();
        } else {
            final List<Artifact> artifacts = new ArrayList<>();
            final Artifact artifact = artifactHashMap.get(submissionParameter.getArtifactName());
            artifacts.add(artifact);
            ArtifactsWorkspaceSettings.getInstance(project).setArtifactsToBuild(artifacts);

            ApplicationManager.getApplication().invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    final CompileScope scope = ArtifactCompileScope.createArtifactsScope(project, artifacts, true);
                    CompilerManager.getInstance(project).make(scope, new CompileStatusNotification() {
                        @Override
                        public void finished(boolean aborted, int errors, int warnings, CompileContext compileContext) {
                            if (aborted || errors != 0) {
                                String errorMessage = StringUtils.join(
                                        compileContext.getMessages(CompilerMessageCategory.ERROR),
                                        "\\n");

                                postEventProperty.put("IsSubmitSucceed", "false");
                                postEventProperty.put("SubmitFailedReason", HDInsightUtil.normalizeTelemetryMessage(errorMessage));
                                AppInsightsClient.create(HDInsightBundle.message("SparkProjectCompileFailed"), null, postEventProperty);

                                showCompilerErrorMessage(compileContext);
                                HDInsightUtil.getJobStatusManager(project).setJobRunningState(false);
                            } else {
                                postEventProperty.put("IsSubmitSucceed", "true");
                                postEventProperty.put("SubmitFailedReason", "CompileSuccess");
                                AppInsightsClient.create(HDInsightBundle.message("SparkProjectCompileSuccess"), null, postEventProperty);

                                HDInsightUtil.showInfoOnSubmissionMessageWindow(project, String.format("Info : Build %s successfully.", artifact.getOutputFile()));
                                submit();
                            }
                        }
                    });
                }
            }, ModalityState.defaultModalityState());
        }
    }

    public Single<Artifact> buildArtifactObservable(@NotNull String artifactName) {
        Optional<JobStatusManager> jsmOpt = Optional.ofNullable(HDInsightUtil.getJobStatusManager(project));

        return Single.fromEmitter(em -> {
            jsmOpt.ifPresent((jsm) -> jsm.setJobRunningState(true));
            postEventAction();

            if (isLocalArtifact()) {
                em.onError(new NotSupportExecption());
                return;
            }

            final Artifact artifact = artifactHashMap.get(artifactName);
            final List<Artifact> artifacts = Collections.singletonList(artifact);

            ArtifactsWorkspaceSettings.getInstance(project).setArtifactsToBuild(artifacts);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                final CompileScope scope = ArtifactCompileScope.createArtifactsScope(
                        project,
                        artifacts,
                        true);

                CompilerManager.getInstance(project).make(
                        scope,
                        (aborted, errors, warnings, compileContext) -> {
                            if (aborted || errors != 0) {
                                showCompilerErrorMessage(compileContext);
                                jsmOpt.ifPresent((jsm) -> jsm.setJobRunningState(false));
                                String errorMessage = StringUtils.join(
                                        compileContext.getMessages(CompilerMessageCategory.ERROR),
                                        "\\n");

                                postEventProperty.put("IsSubmitSucceed", "false");
                                postEventProperty.put("SubmitFailedReason", HDInsightUtil.normalizeTelemetryMessage(errorMessage));

                                AppInsightsClient.create(
                                        HDInsightBundle.message("SparkProjectDebugCompileFailed"),
                                        null,
                                        postEventProperty);

                                em.onError(new CompilationException(errorMessage));
                            } else {
                                postEventProperty.put("IsSubmitSucceed", "true");
                                postEventProperty.put("SubmitFailedReason", "CompileSuccess");

                                AppInsightsClient.create(
                                        HDInsightBundle.message("SparkProjectDebugCompileSuccess"),
                                        null,
                                        postEventProperty);

                                HDInsightUtil.showInfoOnSubmissionMessageWindow(
                                        project,
                                        String.format("Info : Build %s successfully.", artifact.getOutputFile()));

                                em.onSuccess(artifact);
                            }
                });
            }, ModalityState.defaultModalityState());
        });
    }

    private void showCompilerErrorMessage(@NotNull CompileContext compileContext) {
        CompilerMessage[] errorMessages= compileContext.getMessages(CompilerMessageCategory.ERROR);
        for(CompilerMessage message : errorMessages) {
            HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project, message.toString());
        }
    }

    private String getCluserTitle(@NotNull IClusterDetail clusterDetail) {
        String sparkVersion = clusterDetail.getSparkVersion();
        return sparkVersion == null ? clusterDetail.getName() : StringHelper.concat(clusterDetail.getName(), "(Spark: ", sparkVersion, ")");
    }
    private String getCluserTitle(@NotNull String clusterName) {
        for(IClusterDetail clusterDetail : cachedClusterDetails) {
            if(clusterDetail.getName().equals(clusterName)) {
                return getCluserTitle(clusterDetail);
            }
        }
        return "unknown";
    }

    public void uploadFileToCluster(@NotNull final IClusterDetail selectedClusterDetail, @NotNull final String selectedArtifactName) throws Exception{
        String buildJarPath = submissionParameter.isLocalArtifact() ?
                submissionParameter.getLocalArtifactPath() : ((artifactHashMap.get(selectedArtifactName).getOutputFilePath()));

        String filePath = selectedClusterDetail.isEmulator() ?
                SparkSubmitHelper.uploadFileToEmulator(project, selectedClusterDetail, buildJarPath) :
                SparkSubmitHelper.uploadFileToHDFS(project, selectedClusterDetail, buildJarPath);
        submissionParameter.setFilePath(filePath);
    }

    private void tryToCreateBatchSparkJob(@NotNull final IClusterDetail selectedClusterDetail) throws HDIException,IOException {
        SparkBatchSubmission.getInstance().setCredentialsProvider(selectedClusterDetail.getHttpUserName(), selectedClusterDetail.getHttpPassword());
        HttpResponse response = SparkBatchSubmission.getInstance().createBatchSparkJob(SparkSubmitHelper.getLivyConnectionURL(selectedClusterDetail), submissionParameter);

        if (response.getCode() == 201 || response.getCode() == 200) {
            HDInsightUtil.showInfoOnSubmissionMessageWindow(project, "Info : Submit to spark cluster successfully.");
            postEventProperty.put("IsSubmitSucceed", "true");

            String jobLink = selectedClusterDetail.isEmulator() ?
                    ((EmulatorClusterDetail)selectedClusterDetail).getSparkHistoryEndpoint() :
                    String.format("%s/sparkhistory", selectedClusterDetail.getConnectionUrl());
            HDInsightUtil.getSparkSubmissionToolWindowManager(project).setHyperLinkWithText("See spark job view from ", jobLink, jobLink);
            SparkSubmitResponse sparkSubmitResponse = new Gson().fromJson(response.getMessage(), new TypeToken<SparkSubmitResponse>() {
            }.getType());

            // Set submitted spark application id and http request info for stopping running application
            HDInsightUtil.getSparkSubmissionToolWindowManager(project).setSparkApplicationStopInfo(selectedClusterDetail, sparkSubmitResponse.getId());
            HDInsightUtil.getSparkSubmissionToolWindowManager(project).setStopButtonState(true);
            HDInsightUtil.getSparkSubmissionToolWindowManager(project).getJobStatusManager().resetJobStateManager();
            SparkSubmitHelper.getInstance().printRunningLogStreamingly(project, sparkSubmitResponse.getId(), selectedClusterDetail, postEventProperty);
        } else {
            HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project,
                    String.format("Error : Failed to submit to spark cluster. error code : %d, reason :  %s.", response.getCode(), response.getContent()));
            postEventProperty.put("IsSubmitSucceed", "false");
            postEventProperty.put("SubmitFailedReason", response.getContent());
            AppInsightsClient.create(HDInsightBundle.message("SparkSubmissionButtonClickEvent"), null, postEventProperty);
        }
    }

    public Single<String> jobLogObservable(int batchId, @NotNull final IClusterDetail selectedClusterDetail) {
        return Single.create(em -> {
                HDInsightUtil.showInfoOnSubmissionMessageWindow(
                        project, "Info : Submit to spark cluster for debugging successfully.");

                try {
                    // Blocking function, exit when the Spark job stops running
                    SparkSubmitHelper.getInstance().printRunningLogStreamingly(
                            project, batchId, selectedClusterDetail, postEventProperty);

                    em.onSuccess("done");
                } catch (IOException ex) {
                    em.onSuccess(ex.getMessage());
                }
        });
    }

    public SparkBatchRemoteDebugJob tryToCreateBatchSparkDebugJob(@NotNull final IClusterDetail selectedClusterDetail) throws HDIException,IOException {
        SparkBatchSubmission.getInstance().setCredentialsProvider(selectedClusterDetail.getHttpUserName(), selectedClusterDetail.getHttpPassword());

        try {
            SparkBatchRemoteDebugJob debugJob = SparkBatchRemoteDebugJob.factory(
                    SparkSubmitHelper.getLivyConnectionURL(selectedClusterDetail),
                    submissionParameter,
                    SparkBatchSubmission.getInstance());

            debugJob.createBatchSparkJobWithDriverDebugging();

            return debugJob;
        } catch (URISyntaxException ex) {
            throw new HDIException(
                    "Bad Livy Connection URL " + SparkSubmitHelper.getLivyConnectionURL(selectedClusterDetail),
                    ex);
        } catch (IOException ex) {
            HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(
                    project,
                    String.format("Error : Failed to submit to spark cluster. error message : %s.",
                            ex.getMessage()));
            throw ex;
        }
    }

    private void showFailedSubmitErrorMessage(Exception exception) {
        HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project, "Error : Failed to submit application to spark cluster. Exception : " + exception.getMessage());
        postEventProperty.put("IsSubmitSucceed", "false");
        postEventProperty.put("SubmitFailedReason", exception.toString());
        AppInsightsClient.create(HDInsightBundle.message("SparkSubmissionButtonClickEvent"), null, postEventProperty);
    }

    private void writeJobLogToLocal() {
        String path = null;
        try {
            path = SparkSubmitHelper.getInstance().writeLogToLocalFile(project);
        } catch (IOException e) {
            HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project, e.getMessage());
        }

        if (!StringHelper.isNullOrWhiteSpace(path)) {
            String urlPath = StringHelper.concat("file:", path);
            HDInsightUtil.getSparkSubmissionToolWindowManager(project).setHyperLinkWithText("See detailed job log from local:", urlPath, path);
        }
    }

    private IClusterDetail getClusterConfiguration(@NotNull final IClusterDetail selectedClusterDetail, @NotNull final boolean isFirstSubmit) {
        try {
            if (!selectedClusterDetail.isConfigInfoAvailable()) {
                selectedClusterDetail.getConfigurationInfo(getProject());
            }
        } catch (AuthenticationException authenticationException) {
            if (isFirstSubmit) {
                HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project, "Error: Cluster Credentials Expired, Please sign in again...");
                //get new credentials by call getClusterDetails
                cachedClusterDetails.clear();
                cachedClusterDetails.addAll(ClusterManagerEx.getInstance().getClusterDetails(getProject()));

                for (IClusterDetail iClusterDetail : cachedClusterDetails) {
                    if (iClusterDetail.getName().equalsIgnoreCase(selectedClusterDetail.getName())) {
                        //retry get cluster info
                        return getClusterConfiguration(iClusterDetail, false);
                    }
                }
            } else {
                return null;
            }
        } catch (Exception exception) {
            showFailedSubmitErrorMessage(exception);
            return null;
        }

        return selectedClusterDetail;
    }

    private void submit() {
        DefaultLoader.getIdeHelper().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                IClusterDetail selectedClusterDetail = mapClusterNameToClusterDetail.get(clusterComboBoxModel.getSelectedItem());
                String selectedArtifactName = submissionParameter.getArtifactName();

                //may get a new clusterDetail reference if cluster credentials expired
                selectedClusterDetail = getClusterConfiguration(selectedClusterDetail, true);

                if (selectedClusterDetail == null) {
                    HDInsightUtil.showErrorMessageOnSubmissionMessageWindow(project, "Selected Cluster can not found. Please login in first in HDInsight Explorer and try submit job again");
                    return;
                }

                try {
                    uploadFileToCluster(selectedClusterDetail, selectedArtifactName);
                    tryToCreateBatchSparkJob(selectedClusterDetail);
                } catch (Exception exception) {
                    showFailedSubmitErrorMessage(exception);
                } finally {
                    HDInsightUtil.getSparkSubmissionToolWindowManager(project).setStopButtonState(false);
                    HDInsightUtil.getSparkSubmissionToolWindowManager(project).setBrowserButtonState(false);

                    if (HDInsightUtil.getSparkSubmissionToolWindowManager(project).getJobStatusManager().isApplicationGenerated()) {
                        String applicationId = HDInsightUtil.getSparkSubmissionToolWindowManager(project).getJobStatusManager().getApplicationId();

                        // ApplicationYarnUrl example : https://sparklivylogtest.azurehdinsight.net/yarnui/hn/cluster/app/application_01_111
                        String applicationYarnUrl = String.format(SparkYarnLogUrlFormat, selectedClusterDetail.getConnectionUrl(), applicationId);
                        HDInsightUtil.getSparkSubmissionToolWindowManager(project).setHyperLinkWithText("See detailed job information from ", applicationYarnUrl, applicationYarnUrl);

                        writeJobLogToLocal();
                    }

                    HDInsightUtil.getJobStatusManager(project).setJobRunningState(false);
                }
            }
        });
    }

    private void postEventAction() {
        postEventProperty.clear();
        postEventProperty.put("ClusterName", submissionParameter.getClusterName());
        if (submissionParameter.getArgs() != null && submissionParameter.getArgs().size() > 0) {
            postEventProperty.put("HasCommandLine", "true");
        } else {
            postEventProperty.put("HasCommandLine", "false");
        }

        if (submissionParameter.getReferencedFiles() != null && submissionParameter.getReferencedFiles().size() > 0) {
            postEventProperty.put("HasReferencedFile", "true");
        } else {
            postEventProperty.put("HasReferencedFile", "false");
        }

        if (submissionParameter.getReferencedJars() != null && submissionParameter.getReferencedJars().size() > 0) {
            postEventProperty.put("HasReferencedJar", "true");
        } else {
            postEventProperty.put("HasReferencedJar", "false");
        }
    }

    public Map<String, Object> getJobConfigMap() {
        return tableModel.getJobConfigMap();
    }

    public void loadJobConfigMapFromPropertyFile(String propertyFilePath) { tableModel.loadJobConfigMapFromPropertyFile(propertyFilePath);}

    private void initializeTableModel(final InteractiveTableModel tableModel) {
        if (submissionParameter == null) {
            for (int i = 0; i < SparkSubmissionParameter.defaultParameters.length; ++i) {
                tableModel.addRow(SparkSubmissionParameter.defaultParameters[i].first(), "");
            }
        } else {
            Map<String, Object> configs = submissionParameter.getJobConfig();

            Arrays.stream(SparkSubmissionParameter.parameterList).forEach(key -> {
                // Put the default empty value into the submission parameters job configuration
                if (!configs.containsKey(key)) {
                    configs.put(key, "");
                }

                tableModel.addRow(key, configs.get(key));
            });

            for (Map.Entry<String, Object> jobConfigEntry : configs.entrySet()) {
                String jobConfigKey = jobConfigEntry.getKey();
                Object jobConfigValue = jobConfigEntry.getValue();

                if (!StringHelper.isNullOrWhiteSpace(jobConfigKey) && !SparkSubmissionParameter.isSubmissionParameter(jobConfigKey)) {
                    if (jobConfigKey.equals(SparkSubmissionParameter.Conf)) {
                        SparkConfigures sparkConfigs;

                        if (jobConfigValue instanceof Map && !(sparkConfigs = new SparkConfigures(jobConfigValue)).isEmpty()) {
                            for (Map.Entry<String, Object> sparkConfigEntry : sparkConfigs.entrySet()) {
                                if (!StringHelper.isNullOrWhiteSpace(sparkConfigEntry.getKey())) {
                                    tableModel.addRow(sparkConfigEntry.getKey(), sparkConfigEntry.getValue());
                                }
                            }
                        }
                    } else {
                        tableModel.addRow(jobConfigKey, jobConfigValue);
                    }
                }
            }
        }

        if (!tableModel.hasEmptyRow()) {
            tableModel.addEmptyRow();
        }
    }

    public Element exportToElement() {
        Element submitModelElement = new Element(SUBMISSION_CONTENT_NAME);

        SparkSubmissionParameter submissionParameter = getSubmissionParameter();

        if (submissionParameter == null) {
            return submitModelElement;
        }

        submitModelElement.setAttribute(
                SUBMISSION_ATTRIBUTE_CLUSTER_NAME, submissionParameter.getClusterName());
        submitModelElement.setAttribute(
                SUBMISSION_ATTRIBUTE_IS_LOCAL_ARTIFACT, Boolean.toString(isLocalArtifact()));
        submitModelElement.setAttribute(
                SUBMISSION_ATTRIBUTE_ARTIFACT_NAME, submissionParameter.getArtifactName());
        submitModelElement.setAttribute(
                SUBMISSION_ATTRIBUTE_CLASSNAME, submissionParameter.getMainClassName());

        Map<String, Object> jobConf = submissionParameter.getJobConfig();
        Element jobConfElement = new Element(SUBMISSION_CONTENT_JOB_CONF);

        jobConfElement.setAttributes(Stream.concat(
                        jobConf.entrySet().stream()
                                .filter(entry -> SparkSubmissionParameter.isSubmissionParameter(entry.getKey())),
                        // The Spark Job Configuration needs to be separated
                        jobConf.entrySet().stream()
                                .filter(entry -> !SparkSubmissionParameter.isSubmissionParameter(entry.getKey()))
                                .filter(entry -> entry.getKey().equals(SparkSubmissionParameter.Conf))
                                .flatMap(entry -> new SparkConfigures(entry.getValue()).entrySet().stream())
                )
                .filter(entry -> !entry.getKey().trim().isEmpty())
                .map(entry -> new Attribute(entry.getKey(), entry.getValue().toString()))
                .collect(Collectors.toList()));

        submitModelElement.addContent(jobConfElement);

        Element cmdLineArgsElement = new Element(SUBMISSION_CONTENT_COMMAND_LINE_ARGS);
        cmdLineArgsElement.addContent(
                submissionParameter.getArgs().stream()
                        .filter(e -> !e.trim().isEmpty())
                        .map(Text::new).collect(Collectors.toList()));
        submitModelElement.addContent(cmdLineArgsElement);

        Element refJarsElement = new Element(SUBMISSION_CONTENT_REF_JARS);
        refJarsElement.addContent(
                submissionParameter.getReferencedJars().stream()
                        .filter(e -> !e.trim().isEmpty())
                        .map(Text::new).collect(Collectors.toList()));
        submitModelElement.addContent(refJarsElement);

        Element refFilesElement = new Element(SUBMISSION_CONTENT_REF_FILES);
        refFilesElement.addContent(
                submissionParameter.getReferencedFiles().stream()
                        .filter(e -> !e.trim().isEmpty())
                        .map(Text::new).collect(Collectors.toList()));
        submitModelElement.addContent(refFilesElement);

        SparkSubmitAdvancedConfigModel advConfModel = getAdvancedConfigModel();
        if (advConfModel != null && advConfModel.enableRemoteDebug) {
            Element sshCertElement = new Element(SUBMISSION_CONTENT_SSH_CERT);
            sshCertElement.setAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_USER_NAME, advConfModel.sshUserName);

            sshCertElement.setAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_AUTHTYPE_NAME, advConfModel.sshAuthType.name());
            if (advConfModel.sshAuthType == SparkSubmitAdvancedConfigModel.SSHAuthType.UseKeyFile) {
                sshCertElement.setAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_PRIVATE_KEYPATH_NAME, advConfModel.sshKyeFile.toString());
            }

            submitModelElement.addContent(sshCertElement);
        }

        return submitModelElement;
    }

    static public SparkSubmitModel factoryFromElement(@NotNull Project project, @NotNull Element rootElement)
            throws InvalidDataException{
        Attribute nilValueAttribute = new Attribute("Nil", "");
        Attribute falseValueAttribute = new Attribute("False", "false");
        Element emptyElement = new Element("Empty");

        return Optional.ofNullable(rootElement.getChild(SUBMISSION_CONTENT_NAME)).map(element -> {
            SubmissionTableModel tableModel = new SubmissionTableModel(
                    new String[]{"Key", "Value", ""});

            Optional.ofNullable(element.getChild(SUBMISSION_CONTENT_JOB_CONF))
                    .ifPresent(jobConfElem -> {
                        jobConfElem.getAttributes().forEach(attr -> {
                            tableModel.addRow(attr.getName(), attr.getValue());
                        });
                    });

            SparkSubmissionParameter parameter = new SparkSubmissionParameter(
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_CLUSTER_NAME))
                            .orElse(nilValueAttribute).getValue(),
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_IS_LOCAL_ARTIFACT))
                            .orElse(falseValueAttribute).getValue().toLowerCase().equals("true"),
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_ARTIFACT_NAME))
                            .orElse(nilValueAttribute).getValue(),
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_LOCAL_ARTIFACT_PATH))
                            .orElse(nilValueAttribute).getValue(),
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_FILE_PATH))
                            .orElse(nilValueAttribute).getValue(),
                    Optional.ofNullable(element.getAttribute(SUBMISSION_ATTRIBUTE_CLASSNAME))
                            .orElse(nilValueAttribute).getValue(),
                    Optional.ofNullable(element.getChild(SUBMISSION_CONTENT_REF_FILES))
                            .orElse(emptyElement).getContent().stream().map(cont -> ((Text) cont).getText()).collect
                            (Collectors.toList()),
                    Optional.ofNullable(element.getChild(SUBMISSION_CONTENT_REF_JARS))
                            .orElse(emptyElement).getContent().stream().map(cont -> ((Text) cont).getText()).collect
                            (Collectors.toList()),
                    Optional.ofNullable(element.getChild(SUBMISSION_CONTENT_COMMAND_LINE_ARGS))
                            .orElse(emptyElement).getContent().stream().map(cont -> ((Text) cont).getText()).collect
                            (Collectors.toList()),
                    tableModel.getJobConfigMap()
            );

            SparkSubmitModel newSubmitModel = new SparkSubmitModel(project, parameter);
            Optional.ofNullable(element.getChild(SUBMISSION_CONTENT_SSH_CERT))
                    .ifPresent(sshCertElem -> {
                        SparkSubmitAdvancedConfigModel advConfigModel = new SparkSubmitAdvancedConfigModel();

                        advConfigModel.enableRemoteDebug = true;

                        Optional.ofNullable(sshCertElem.getAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_USER_NAME))
                                .ifPresent(attribute -> advConfigModel.sshUserName = attribute.getValue());
                        Optional.ofNullable(sshCertElem.getAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_AUTHTYPE_NAME))
                                .ifPresent(attribute -> advConfigModel.sshAuthType =
                                        SparkSubmitAdvancedConfigModel.SSHAuthType.valueOf(attribute.getValue()));
                        Optional.ofNullable(sshCertElem.getAttribute(SUBMISSION_ATTRIBUTE_SSH_CERT_PRIVATE_KEYPATH_NAME))
                                .ifPresent(attribute -> advConfigModel.sshKyeFile = new File(attribute.getValue()));

                        newSubmitModel.setAdvancedConfigModel(advConfigModel);
                    });

            return newSubmitModel;
        }).orElseThrow(() -> new InvalidDataException("Bad submission parameters"));
    }
}
