/*
 * generated by Xtext
 */
package org.eclipse.xtext.xbase.ui

import org.eclipse.jface.viewers.ILabelProvider
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.eclipse.xtext.ui.editor.copyqualifiedname.CopyQualifiedNameService
import org.eclipse.xtext.ui.editor.hover.html.IEObjectHoverDocumentationProvider
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfiguration
import org.eclipse.xtext.ui.resource.ResourceServiceDescriptionLabelProvider
import org.eclipse.xtext.validation.IssueSeveritiesProvider
import org.eclipse.xtext.xbase.typesystem.internal.OptimizingFeatureScopeTrackerProvider
import org.eclipse.xtext.xbase.typesystem.internal.IFeatureScopeTracker
import org.eclipse.xtext.xbase.ui.editor.copyqualifiedname.XbaseCopyQualifiedNameService
import org.eclipse.xtext.xbase.ui.highlighting.XbaseHighlightingConfiguration
import org.eclipse.xtext.xbase.ui.hover.XbaseHoverDocumentationProvider
import org.eclipse.xtext.xbase.ui.labeling.XbaseDescriptionLabelProvider
import org.eclipse.xtext.xbase.ui.labeling.XbaseLabelProvider
import org.eclipse.xtext.xbase.ui.validation.XbaseIssueSeveritiesProvider
import com.google.inject.Binder

/** 
 * Use this class to register components to be used within the IDE.
 */
@SuppressWarnings("restriction") class XbaseUiModule extends org.eclipse.xtext.xbase.ui.AbstractXbaseUiModule {

	new(AbstractUIPlugin plugin) {
		super(plugin)
	}

	override Class<? extends IHighlightingConfiguration> bindIHighlightingConfiguration() {
		return XbaseHighlightingConfiguration
	}

	override Class<? extends IEObjectHoverDocumentationProvider> bindIEObjectHoverDocumentationProvider() {
		return XbaseHoverDocumentationProvider
	}

	def Class<? extends IssueSeveritiesProvider> bindIssueSeverityServiceProvider() {
		return XbaseIssueSeveritiesProvider
	}

	override Class<? extends ILabelProvider> bindILabelProvider() {
		return XbaseLabelProvider
	}

	override void configureResourceUIServiceLabelProvider(Binder binder) {
		binder.bind(ILabelProvider).annotatedWith(ResourceServiceDescriptionLabelProvider).to(XbaseDescriptionLabelProvider)
	}

	override Class<? extends CopyQualifiedNameService> bindCopyQualifiedNameService() {
		return XbaseCopyQualifiedNameService
	}

	override Class<? extends IFeatureScopeTracker.Provider> bindIFeatureScopeTracker$Provider() {
		return OptimizingFeatureScopeTrackerProvider
	}
}