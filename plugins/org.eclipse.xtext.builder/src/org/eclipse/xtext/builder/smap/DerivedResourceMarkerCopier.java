/*******************************************************************************
 * Copyright (c) 2014 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.builder.smap;

import static com.google.common.collect.Sets.*;

import java.util.Iterator;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.eclipse.xtext.generator.trace.ILocationInResource;
import org.eclipse.xtext.generator.trace.ITrace;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.MarkerTypes;
import org.eclipse.xtext.ui.validation.MarkerTypeProvider;
import org.eclipse.xtext.util.ITextRegionWithLineInformation;
import org.eclipse.xtext.util.TextRegion;
import org.eclipse.xtext.validation.CheckType;
import org.eclipse.xtext.validation.Issue;

import com.google.inject.Inject;

/**
 * This class copies the error markers from a given derived resources into its source resource.
 * 
 * @author Dennis Huebner - Initial contribution and API
 */
public class DerivedResourceMarkerCopier {
	private static final Logger LOG = Logger.getLogger(DerivedResourceMarkerCopier.class);

	public static final String COPIED_FROM_FILE = "fromFile";

	@Inject
	private IResourceServiceProvider.Registry serviceProviderRegistry;

	/**
	 * Looks for JDT error marker in javaFile and adds a copy to the corresponding<br>
	 * source resource. If the source resource already contains error marker, nothing will be added.
	 *
	 * @param javaFile
	 *            - Java file to inspect. Target file.
	 * @param traceToSource
	 *            - trace information to use. Points to the source file.
	 */
	public void reflectErrorMarkerInSource(IFile javaFile, ITrace traceToSource) throws CoreException {
		//TODO use preference store
		int maxSeverity = IMarker.SEVERITY_ERROR;

		IFile srcFile = findSourceFile(traceToSource, javaFile.getWorkspace());
		if (srcFile == null) {
			return;
		}
		// clean up marker in source file derived from this java file
		cleanUpCreatedMarkers(javaFile, srcFile);

		Set<IMarker> problemsInJava = findJavaProblemMarker(javaFile, maxSeverity);
		// Any problems found in target, nothing to copy -> return
		if (problemsInJava.size() == 0) {
			return;
		}

		// Do nothing if Source file already has own problems
		if (!hasPlainXtextProblemMarker(srcFile, maxSeverity)) {
			copyProblemMarker(javaFile, traceToSource, problemsInJava, srcFile);
		}

	}

	private void copyProblemMarker(IFile javaFile, ITrace traceToSource, Set<IMarker> problemsInJava, IFile srcFile)
			throws CoreException {
		String sourceMarkerType = null;
		for (IMarker marker : problemsInJava) {
			String message = (String) marker.getAttribute(IMarker.MESSAGE);
			if (message == null) {
				continue;
			}
			Integer charStart = marker.getAttribute(IMarker.CHAR_START, 0);
			Integer charEnd = marker.getAttribute(IMarker.CHAR_END, 0);
			int severity = MarkerUtilities.getSeverity(marker);

			ILocationInResource associatedLocation = traceToSource.getBestAssociatedLocation(new TextRegion(charStart,
					charEnd - charStart));
			if (associatedLocation != null) {
				if (sourceMarkerType == null) {
					sourceMarkerType = determinateMarkerTypeByURI(associatedLocation.getSrcRelativeResourceURI());
				}
				if (!srcFile.equals(findIFile(associatedLocation, srcFile.getWorkspace()))) {
					LOG.error("File in associated location is not the same as main source file.");
				}
				IMarker xtendMarker = srcFile.createMarker(sourceMarkerType);
				xtendMarker.setAttribute(IMarker.MESSAGE, "Java problem: " + message);
				xtendMarker.setAttribute(IMarker.SEVERITY, severity);
				ITextRegionWithLineInformation region = associatedLocation.getTextRegion();
				xtendMarker.setAttribute(IMarker.LINE_NUMBER, region.getLineNumber());
				xtendMarker.setAttribute(IMarker.CHAR_START, region.getOffset());
				xtendMarker.setAttribute(IMarker.CHAR_END, region.getOffset() + region.getLength());
				xtendMarker.setAttribute(COPIED_FROM_FILE, javaFile.getFullPath().toString());
			}
		}

	}

	private String determinateMarkerTypeByURI(URI resourceUri) {
		IResourceServiceProvider serviceProvider = serviceProviderRegistry.getResourceServiceProvider(resourceUri);
		if (serviceProvider == null)
			return null;
		MarkerTypeProvider typeProvider = serviceProvider.get(MarkerTypeProvider.class);
		Issue.IssueImpl issue = new Issue.IssueImpl();
		issue.setType(CheckType.NORMAL);
		return typeProvider.getMarkerType(issue);
	}

	private Set<IMarker> findJavaProblemMarker(IFile javaFile, int maxSeverity) throws CoreException {
		Set<IMarker> problems = newHashSet();
		for (IMarker marker : javaFile.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true,
				IResource.DEPTH_ZERO)) {
			if (MarkerUtilities.getSeverity(marker) >= maxSeverity) {
				problems.add(marker);
			}
		}
		return problems;
	}

	/**
	 * @return <code>true</code> if srcFile contains none-derived problem marker >= <code>maxSeverity</code>
	 */
	private boolean hasPlainXtextProblemMarker(IFile srcFile, int maxSeverity) throws CoreException {
		for (IMarker iMarker : srcFile.findMarkers(MarkerTypes.ANY_VALIDATION, true, IResource.DEPTH_ZERO)) {
			if (MarkerUtilities.getSeverity(iMarker) >= maxSeverity && iMarker.getAttribute(COPIED_FROM_FILE) == null) {
				return true;
			}
		}
		return false;
	}

	private void cleanUpCreatedMarkers(IFile javaFile, IResource srcFile) throws CoreException {
		for (IMarker iMarker : srcFile.findMarkers(MarkerTypes.ANY_VALIDATION, true, IResource.DEPTH_ZERO)) {
			if (javaFile.getFullPath().toString().equals(iMarker.getAttribute(COPIED_FROM_FILE, ""))) {
				iMarker.delete();
			}
		}
	}

	private IFile findSourceFile(ITrace traceToSource, IWorkspace workspace) {
		Iterator<ILocationInResource> iterator = traceToSource.getAllAssociatedLocations().iterator();
		if (iterator.hasNext()) {
			ILocationInResource srcLocation = iterator.next();
			return findIFile(srcLocation, workspace);
		}
		return null;
	}

	private IFile findIFile(ILocationInResource locationInResource, IWorkspace workspace) {
		IStorage storage;
		try {
			storage = locationInResource.getStorage();
		} catch (IllegalStateException e) {
			LOG.error("Failed to find Storage", e);
			return null;
		}
		return workspace.getRoot().getFile(storage.getFullPath());
	}
}