/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.idea.intention;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;

import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.Iconable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.idea.catalog.CamelCatalogService;
import org.apache.camel.idea.model.ComponentModel;
import org.apache.camel.idea.model.ModelHelper;
import org.apache.camel.idea.util.CamelService;
import org.apache.camel.idea.util.IdeaUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import static org.apache.camel.idea.CamelContributor.CAMEL_ICON;
import static org.apache.camel.idea.util.IdeaUtils.extractTextFromElement;

public class CamelAddEndpointIntention extends PsiElementBaseIntentionAction implements Iconable, LowPriorityAction {

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
         // filter libraries to only be Camel libraries
        Set<Library> processedLibraries = ServiceManager.getService(project, CamelService.class).getLibraries();
        Set<String> artifacts = new LinkedHashSet<>();
        for (Library lib : processedLibraries) {
            String name = lib.getName();
            if (name != null && name.startsWith("Maven: org.apache.camel:")) {
                name = name.substring(24);
                String artifactId = name.substring(0, name.indexOf(":"));
                artifacts.add(artifactId);
            }
        }

        // find the camel component from those libraries
        boolean consumerOnly = IdeaUtils.isConsumerEndpoint(element);
        List<String> names = findCamelComponentNamesInArtifact(artifacts, consumerOnly, project);

        // no camel endpoints then exit
        if (names.isEmpty()) {
            return;
        }

        // show popup to chose the component
        JBList list = new JBList(names.toArray(new String[names.size()]));
        PopupChooserBuilder builder = JBPopupFactory.getInstance().createListPopupBuilder(list);
        builder.setAdText(names.size() + " components");
        builder.setTitle("Add Camel Endpoint");
        builder.setItemChoosenCallback(() -> {
            String line = (String) list.getSelectedValue();
            int pos = editor.getCaretModel().getCurrentCaret().getOffset();
            if (pos > 0) {
                // must run this as write action because we change the source code
                new WriteCommandAction(project, element.getContainingFile()) {
                    @Override
                    protected void run(@NotNull Result result) throws Throwable {
                        String text = line + ":";
                        editor.getDocument().insertString(pos, text);
                        editor.getCaretModel().moveToOffset(pos + text.length());
                    }
                }.execute();
            }
        });

        JBPopup popup = builder.createPopup();
        popup.showInBestPositionFor(editor);
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (ServiceManager.getService(project, CamelService.class).isCamelPresent()) {
            // special for xml
            XmlTag xml = PsiTreeUtil.getParentOfType(element, XmlTag.class);
            if (xml != null) {
                // special check for poll enrich where we add the endpoint on a child node (camel expression)
                XmlTag parent = xml.getParentTag();
                if (parent != null && parent.getLocalName().equals("pollEnrich")) {
                    return true;
                }
            }

            String text = extractTextFromElement(element);
            return text != null && text.trim().isEmpty();
        }
        return false;
    }

    @NotNull
    @Override
    public String getText() {
        return "Add camel endpoint";
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
        return "Apache Camel";
    }

    @Override
    public Icon getIcon(@IconFlags int flags) {
        return CAMEL_ICON;
    }

    private static List<String> findCamelComponentNamesInArtifact(Set<String> artifactIds, boolean consumerOnly, Project project) {
        List<String> names = new ArrayList<>();

        CamelCatalog camelCatalog = ServiceManager.getService(project, CamelCatalogService.class).get();
        for (String name : camelCatalog.findComponentNames()) {
            String json = camelCatalog.componentJSonSchema(name);
            ComponentModel model = ModelHelper.generateComponentModel(json, false);
            if (artifactIds.contains(model.getArtifactId())) {
                boolean onlyConsume = "true".equals(model.getConsumerOnly());
                boolean onlyProduce = "true".equals(model.getProducerOnly());
                boolean both = !onlyConsume && !onlyProduce;

                if (both) {
                    names.add(name);
                } else if (consumerOnly && onlyConsume) {
                    names.add(name);
                } else if (!consumerOnly && onlyProduce) {
                    names.add(name);
                }
            }
        }

        // sort
        Collections.sort(names);

        return names;
    }

}
