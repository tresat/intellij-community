/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.vcs.log.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.vcs.log.VcsLogProvider;
import com.intellij.vcs.log.VcsLogRefresher;
import com.intellij.vcs.log.data.VcsLogDataManager;
import com.intellij.vcs.log.data.VcsLogFilterer;
import com.intellij.vcs.log.data.VcsLogFiltererImpl;
import com.intellij.vcs.log.data.VcsLogUiProperties;
import com.intellij.vcs.log.graph.PermanentGraph;
import com.intellij.vcs.log.ui.VcsLogColorManagerImpl;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

public class VcsLogManager implements Disposable {

  public static final ExtensionPointName<VcsLogProvider> LOG_PROVIDER_EP = ExtensionPointName.create("com.intellij.logProvider");
  private static final Logger LOG = Logger.getInstance(VcsLogManager.class);

  @NotNull private final Project myProject;
  @NotNull private final VcsLogUiProperties myUiProperties;

  private volatile VcsLogUiImpl myUi;
  private VcsLogDataManager myDataManager;
  private VcsLogColorManagerImpl myColorManager;
  private VcsLogTabsRefresher myTabsLogRefresher;

  public VcsLogManager(@NotNull Project project, @NotNull VcsLogUiProperties uiProperties) {
    myProject = project;
    myUiProperties = uiProperties;

    Disposer.register(project, this);
  }

  public VcsLogDataManager getDataManager() {
    return myDataManager;
  }

  @NotNull
  protected Collection<VcsRoot> getVcsRoots() {
    return Arrays.asList(ProjectLevelVcsManager.getInstance(myProject).getAllVcsRoots());
  }

  public void watchTab(@NotNull String contentTabName, @NotNull VcsLogFilterer filterer) {
    myTabsLogRefresher.addTabToWatch(contentTabName, filterer);
  }

  public void unwatchTab(@NotNull String contentTabName) {
    myTabsLogRefresher.removeTabFromWatch(contentTabName);
  }

  @NotNull
  public JComponent initMainLog(@Nullable final String contentTabName) {
    myUi = createLog(contentTabName);
    myUi.requestFocus();
    return myUi.getMainFrame().getMainComponent();
  }

  @NotNull
  public VcsLogUiImpl createLog(@Nullable String contentTabName) {
    initData();

    VcsLogFiltererImpl filterer =
      new VcsLogFiltererImpl(myProject, myDataManager, PermanentGraph.SortType.values()[myUiProperties.getBekSortType()]);
    VcsLogUiImpl ui = new VcsLogUiImpl(myDataManager, myProject, myColorManager, myUiProperties, filterer);
    if (contentTabName != null) {
      watchTab(contentTabName, filterer);
    }
    return ui;
  }

  public boolean initData() {
    if (myDataManager != null) return true;

    Map<VirtualFile, VcsLogProvider> logProviders = findLogProviders(getVcsRoots(), myProject);
    myDataManager = new VcsLogDataManager(myProject, logProviders);
    myTabsLogRefresher = new VcsLogTabsRefresher(myProject, myDataManager);

    refreshLogOnVcsEvents(logProviders, myTabsLogRefresher);

    myColorManager = new VcsLogColorManagerImpl(logProviders.keySet());

    myDataManager.refreshCompletely();
    return false;
  }

  private static void refreshLogOnVcsEvents(@NotNull Map<VirtualFile, VcsLogProvider> logProviders, @NotNull VcsLogRefresher refresher) {
    MultiMap<VcsLogProvider, VirtualFile> providers2roots = MultiMap.create();
    for (Map.Entry<VirtualFile, VcsLogProvider> entry : logProviders.entrySet()) {
      providers2roots.putValue(entry.getValue(), entry.getKey());
    }

    for (Map.Entry<VcsLogProvider, Collection<VirtualFile>> entry : providers2roots.entrySet()) {
      entry.getKey().subscribeToRootRefreshEvents(entry.getValue(), refresher);
    }
  }

  @NotNull
  public static Map<VirtualFile, VcsLogProvider> findLogProviders(@NotNull Collection<VcsRoot> roots, @NotNull Project project) {
    Map<VirtualFile, VcsLogProvider> logProviders = ContainerUtil.newHashMap();
    VcsLogProvider[] allLogProviders = Extensions.getExtensions(LOG_PROVIDER_EP, project);
    for (VcsRoot root : roots) {
      AbstractVcs vcs = root.getVcs();
      VirtualFile path = root.getPath();
      if (vcs == null || path == null) {
        LOG.error("Skipping invalid VCS root: " + root);
        continue;
      }

      for (VcsLogProvider provider : allLogProviders) {
        if (provider.getSupportedVcs().equals(vcs.getKeyInstanceMethod())) {
          logProviders.put(path, provider);
          break;
        }
      }
    }
    return logProviders;
  }

  /**
   * The instance of the {@link VcsLogUiImpl} or null if the log was not initialized yet.
   */
  @Nullable
  public VcsLogUiImpl getMainLogUi() {
    return myUi;
  }

  public void disposeLog() {
    if (myDataManager != null) Disposer.dispose(myDataManager);

    myDataManager = null;
    myTabsLogRefresher = null;
    myColorManager = null;
    myUi = null;
  }

  public static VcsLogManager getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, VcsLogManager.class);
  }

  @Override
  public void dispose() {
    disposeLog();
  }
}
