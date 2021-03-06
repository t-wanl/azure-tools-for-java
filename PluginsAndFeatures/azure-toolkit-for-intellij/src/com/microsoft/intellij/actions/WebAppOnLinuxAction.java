/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.intellij.actions;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.RunDialog;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.microsoft.azuretools.authmanage.AuthMethodManager;
import com.microsoft.azuretools.ijidea.actions.AzureSignInAction;
import com.microsoft.azuretools.ijidea.utility.AzureAnAction;
import com.microsoft.intellij.container.Constant;
import com.microsoft.intellij.runner.container.AzureDockerSupportConfigurationType;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class WebAppOnLinuxAction extends AzureAnAction {

    private static final String DIALOG_TITLE = "Run on Web App (Linux)";

    private final ConfigurationType configType;

    public WebAppOnLinuxAction() {
        this.configType = AzureDockerSupportConfigurationType.getInstance();
    }


    @Override
    public void onActionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        try {
            if (AzureSignInAction.doSignIn(AuthMethodManager.getInstance(), project)) {
                ApplicationManager.getApplication().invokeLater(() -> runConfiguration(project));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void update(AnActionEvent event) {
        Project project = DataKeys.PROJECT.getData(event.getDataContext());
        boolean dockerFileExists = false;
        if (project != null) {
            String basePath = project.getBasePath();
            dockerFileExists = basePath != null && Paths.get(basePath, Constant.DOCKER_CONTEXT_FOLDER,
                    Constant.DOCKERFILE_NAME).toFile().exists();
        }
        event.getPresentation().setEnabledAndVisible(dockerFileExists);
    }

    @SuppressWarnings({"deprecation", "Duplicates"})
    private void runConfiguration(Project project) {
        final RunManagerEx manager = RunManagerEx.getInstanceEx(project);
        RunnerAndConfigurationSettings settings = manager.findConfigurationByName(
                String.format("%s:%s", configType.getDisplayName(), project.getName()));
        if (settings == null) {
            final ConfigurationFactory factory = configType.getConfigurationFactories()[0];
            settings = manager.createConfiguration(String.format("%s:%s", configType.getDisplayName(),
                    project.getName()), factory);
        }
        if (RunDialog.editConfiguration(project, settings, DIALOG_TITLE, DefaultRunExecutor.getRunExecutorInstance())) {
            List<BeforeRunTask> tasks = new ArrayList<>(manager.getBeforeRunTasks(settings.getConfiguration()));
            manager.addConfiguration(settings, false, tasks, false);
            manager.setSelectedConfiguration(settings);
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultRunExecutor.getRunExecutorInstance());
        }
    }
}
