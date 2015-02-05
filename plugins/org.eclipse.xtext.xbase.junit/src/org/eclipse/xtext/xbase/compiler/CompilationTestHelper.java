/*******************************************************************************
 * Copyright (c) 2012 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.xbase.compiler;

import static com.google.common.collect.Lists.*;
import static com.google.common.collect.Maps.*;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.generator.IFileSystemAccess;
import org.eclipse.xtext.generator.IGenerator;
import org.eclipse.xtext.generator.IOutputConfigurationProvider;
import org.eclipse.xtext.generator.OutputConfiguration;
import org.eclipse.xtext.junit4.TemporaryFolder;
import org.eclipse.xtext.resource.FileExtensionProvider;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.resource.impl.ResourceDescriptionsData;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.util.Exceptions;
import org.eclipse.xtext.util.Files;
import org.eclipse.xtext.util.IAcceptor;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.xtext.xbase.compiler.RegisteringFileSystemAccess.GeneratedFile;
import org.eclipse.xtext.xbase.file.ProjectConfig;
import org.eclipse.xtext.xbase.file.RuntimeWorkspaceConfigProvider;
import org.eclipse.xtext.xbase.file.SimpleWorkspaceConfig;
import org.eclipse.xtext.xbase.lib.Conversions;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.eclipse.xtext.xbase.lib.Pair;
import org.junit.Assert;

import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * A utility class for testing Xtext languages that compile to Java code.
 * It's designed to be used as an injected extension in unit tests written in Xtend.
 * 
 * Example:
 * <pre>
 * &#64;RunWith(XtextRunner)
 * &#64;InjectWith(MyLanguageInjectorProvider) 
 * class CompilerTest {
 *	
 *	&#64;Rule &#64;Inject public TemporaryFolder temporaryFolder
 *	&#64;Inject extension CompilationTestHelper
 *	
 *	&#64;Test def void myTest() {
 *	  '''
 *	    // DSL code
 *	    Foo bla
 *	  '''.assertCompilesTo('''
 *	    class Foo {
 *	       String bla
 *	    }
 *	  '''
 *	}
 *  }
 * </pre>
 * 
 * @author Sven Efftinge
 * @since 2.7
 * 
 * @noextend This class is not intended to be subclassed by clients.
 */
public class CompilationTestHelper {
	
	public final static String PROJECT_NAME = "myProject";
	
	@Inject private TemporaryFolder temporaryFolder;
	
	@Inject private OnTheFlyJavaCompiler javaCompiler;
	
	@Inject private Provider<XtextResourceSet> resourceSetProvider;
	
	@Inject private FileExtensionProvider extensionProvider;

	@Inject private IOutputConfigurationProvider outputConfigurationProvider;
	
	@Inject private Provider<Result> resultProvider;
	
	private RuntimeWorkspaceConfigProvider configProvider;

	@Inject
	private void setWorkspaceConfig(RuntimeWorkspaceConfigProvider configProvider) {
		this.configProvider = configProvider;
		configureFreshWorkspace();
	}
	
	/**
	 * creates a fresh temp directory and sets it as the workspace root.
	 */
	public void configureFreshWorkspace() {
		File tempDir = createFreshTempDir();
		SimpleWorkspaceConfig config = new SimpleWorkspaceConfig(tempDir.getAbsolutePath());
		ProjectConfig projectConfig = new ProjectConfig(PROJECT_NAME);
		// use default output configuration for the FileSystem Access
		OutputConfiguration outputConfig = null;
		for (OutputConfiguration cfg : getOutputConfigurations()) {
			if (cfg.getName().equals(IFileSystemAccess.DEFAULT_OUTPUT)) {
				outputConfig = cfg;
				break;
			}
		}
		if (outputConfig == null) {
			throw new IllegalStateException("No default output configuration could be found!");
		}
		if (!outputConfig.isUseOutputPerSourceFolder()) {
			String out = outputConfig.getOutputDirectory("src");
			projectConfig.addSourceFolderMapping("src", out);
		} else {
			for (String src : outputConfig.getSourceFolders()) {
				String out = outputConfig.getOutputDirectory(src);
				projectConfig.addSourceFolderMapping(src, out);
			}
		}
		config.addProjectConfig(projectConfig);
		configProvider.setWorkspaceConfig(config);
	}
	
	protected String getSourceFolderPath() {
		return "/"+PROJECT_NAME+"/src";
	}
	
	protected File createFreshTempDir() {
		try {
			return temporaryFolder.newFolder();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
	
	/**
	 * Add the class path entries of the given classes to the java compiler's class path.
	 */
	public void setJavaCompilerClassPath(Class<?> ...classes) {
		javaCompiler.clearClassPath();
		for (Class<?> clazz : classes) {
			javaCompiler.addClassPathOfClass(clazz);
		}
	}
	
	/**
	 * Asserts that the expected code is generated for the given source.
	 * Assumes that
	 * 
	 * @param source some valid source code written in the language under test
	 * @param expected the expected Java source code.
	 * @throws IOException if the resource loading fails 
	 */
	public void assertCompilesTo(CharSequence source, final CharSequence expected) throws IOException {
		final boolean[] called = {false};
		compile(source, new IAcceptor<CompilationTestHelper.Result>() {
			@Override
			public void accept(Result r) {
				Assert.assertEquals(expected.toString(), r.getSingleGeneratedCode());
				called[0] = true;
			}
		});
		Assert.assertTrue("Nothing was generated but the expectation was :\n"+expected, called[0]);
	}
	
	/**
	 * Parses, validates and compiles the given source. Calls the given acceptor for each
	 * resource which is generated from the source.
	 *  
	 * @param source some code written in the language under test.
	 * @param acceptor gets called once for each file generated in {@link IGenerator}
	 * @throws IOException if the resource loading fails 
	 */
	@SuppressWarnings("unchecked")
	public void compile(CharSequence source, IAcceptor<Result> acceptor) throws IOException {
		String fileName = "MyFile."+extensionProvider.getPrimaryFileExtension();
		compile(resourceSet(new Pair<String, CharSequence>(fileName, source)), acceptor);
	}

	/**
	 * Parses, validates and compiles the given sources. Calls the given acceptor for each
	 * resource which is generated from the source.
	 *  
	 * @param sources some inputs written in the language under test.
	 * @param acceptor gets called once for each file generated in {@link IGenerator}
	 * @throws IOException if the resource loading fails
	 * 
	 * @since 2.8
	 */
	@SuppressWarnings("unchecked")
	public void compile(Iterable<? extends CharSequence> sources, IAcceptor<Result> acceptor) throws IOException {
		int index = 0;
		List<Pair<String, ? extends CharSequence>> pairs = newArrayList();
		for (CharSequence source : sources) {
			String fileName = "MyFile" + (++index) + "." + extensionProvider.getPrimaryFileExtension();
			pairs.add(new Pair<String, CharSequence>(fileName, source));
		}
		compile(resourceSet(((Pair<String, ? extends CharSequence>[])Conversions.unwrapArray(pairs, Pair.class))), acceptor);
	}

	/**
	 * Parses, validates and compiles the given source. Calls the given acceptor for each
	 * resource which is generated from the source.
	 *  
	 * @param resourceSet - the {@link ResourceSet} to use
	 * @param acceptor gets called once for each file generated in {@link IGenerator}
	 */
	public void compile(final ResourceSet resourceSet, IAcceptor<Result> acceptor) {
		try {
			List<Resource> resourcesToCheck = newArrayList(resourceSet.getResources());
			Result result = resultProvider.get();
			result.setJavaCompiler(javaCompiler);
			result.setCheckMode(getCheckMode());
			result.setResources(resourcesToCheck);
			result.setResourceSet(resourceSet);
			result.setOutputConfigurations(getOutputConfigurations());
			result.doGenerate();
			acceptor.accept(result);
		} catch (Exception e) {
			Exceptions.throwUncheckedException(e);
		}
	}
	
	/**
	 * @since 2.8
	 */
	protected CheckMode getCheckMode() {
		return CheckMode.NORMAL_AND_FAST;
	}
	
	protected Iterable<? extends OutputConfiguration> getOutputConfigurations() {
		return outputConfigurationProvider.getOutputConfigurations();
	}

	/**
	 * creates a fresh resource set with the given resources
	 * 
	 * @param resources - pairs of file names and their contents 
	 * @return a ResourceSet, containing the given resources.
	 * @throws IOException if the resource loading fails 
	 */
	public ResourceSet resourceSet(Pair<String,? extends CharSequence> ...resources ) throws IOException {
		XtextResourceSet result = resourceSetProvider.get();
		for (Pair<String, ? extends CharSequence> entry : resources) {
			URI uri = copyToWorkspace(getSourceFolderPath()+"/"+entry.getKey(), entry.getValue());
			Resource resource = result.createResource(uri);
			if (resource == null)
				throw new IllegalStateException("Couldn't create resource for URI "+uri+". Resource.Factory not registered?");
			resource.load(newHashMap());
		}
		return result;
	}
	
	/**
	 * Physically copies the given files to the currently used worksapce root (a temporary folder).
	 * @param workspacefilePath the workspace relative path
	 * @param contents the file contents
	 */
	public URI copyToWorkspace(String workspacefilePath, CharSequence contents) {
		File workspaceRoot = new File(this.configProvider.getWorkspaceConfig().getAbsoluteFileSystemPath());
		File fullPath = new File(workspaceRoot.getAbsolutePath()+"/"+workspacefilePath);
		if (fullPath.exists()) {
			fullPath.delete();
		} else {
			mkDir(fullPath.getParentFile());
		}
		URI uri = URI.createFileURI(fullPath.getAbsolutePath());
		Files.writeStringIntoFile(uri.toFileString(), contents.toString());
		return uri;
	}
	
	private void mkDir(File file) {
		if (!file.getParentFile().exists()) {
			mkDir(file.getParentFile());
		}
		if (!file.exists()) {
			file.mkdir();
		}
	}
	
	/**
	 * same as {@link #resourceSet(Pair...)} but without actually loading the created resources.
	 */
	public ResourceSet unLoadedResourceSet(Pair<String,? extends CharSequence> ...resources ) throws IOException {
		XtextResourceSet result = resourceSetProvider.get();
		for (Pair<String, ? extends CharSequence> entry : resources) {
			URI uri = copyToWorkspace(getSourceFolderPath()+"/"+entry.getKey(), entry.getValue());
			Resource resource = result.createResource(uri);
			if (resource == null)
				throw new IllegalStateException("Couldn't create resource for URI "+uri+". Resource.Factory not registered?");
		}
		return result;
	}
	
	/**
	 * A result contains information about various aspects of a compiled piece of code.
	 * 
	 * @noextend This class is not intended to be subclassed by clients.
	 * @noinstantiate This class is not intended to be instantiated by clients.
	 */
	public static class Result {
		
		@Inject private IResourceServiceProvider.Registry serviceRegistry;
		@Inject private Provider<RegisteringFileSystemAccess> fileSystemAccessProvider;
		@Inject	private ElementIssueProvider.Factory elementIssueProviderFactory;
		
		private OnTheFlyJavaCompiler javaCompiler;
		private ResourceSet resourceSet;
		private List<Resource> sources;
		private Map<String,OutputConfiguration> outputConfigurations;
		private CheckMode checkMode;
		
		protected void setResourceSet(ResourceSet resourceSet) {
			this.resourceSet = resourceSet;
		}
		
		/**
		 * @since 2.8
		 */
		protected void setCheckMode(CheckMode checkMode) {
			this.checkMode = checkMode;
		}

		protected void setResources(List<Resource> sources) {
			this.sources = sources;
		}
		
		protected void setJavaCompiler(OnTheFlyJavaCompiler javaCompiler) {
			this.javaCompiler = javaCompiler;
		}
		
		protected void setOutputConfigurations(Iterable<? extends OutputConfiguration> outputConfiguration) {
			this.outputConfigurations = newHashMap();
			for (OutputConfiguration conf : outputConfiguration) {
				outputConfigurations.put(conf.getName(), conf);
			}
		}
		
		private ClassLoader classLoader;
		private Map<String,Class<?>> compiledClasses;
		private Map<String,String> generatedCode;
		private RegisteringFileSystemAccess access;
		private ResourceDescriptionsData index;
		private List<Issue> allErrorsAndWarnings;
		
		/**
		 * Ensures validation has happened and returns any errors and warnings
		 * 
		 * @return errors and warnings contained in the currently processed sources
		 */
		public List<Issue> getErrorsAndWarnings() {
			doValidation();
			return allErrorsAndWarnings;
		}

		/**
		 * Ensures compilation has happened and returns any generated and compiled Java classes.
		 * 
		 * @return the compiled Java classes
		 */
		public Map<String,Class<?>> getCompiledClasses() {
			doCompile();
			return compiledClasses;
		}
		
		/**
		 * Ensures compilation has happened and returns the class loader including compiled classes.
		 * 
		 * @return the class loader after the compilation happend
		 */
		public ClassLoader getClassLoader() {
			doCompile();
			return classLoader;
		}
		
		/**
		 * Ensures generation happened and returns a map of the generated Java source files.
		 * 
		 * @return a map of the generated Java source files, where the key is the qualified class name and the value the generated Java code.
		 */
		public Map<String,String> getGeneratedCode() {
			doGenerate();
			return generatedCode;
		}

		/**
		 * convenience method. Same as getGeneratedCode().get(typeName)
		 */
		public String getGeneratedCode(String typeName) {
			return getGeneratedCode().get(typeName);
		}
		
		/**
		 * Convenience method for the common case, that only one file is generated.
		 */
		public String getSingleGeneratedCode() {
			doGenerate();
			if (access.getTextFiles().size() == 1)
				return access.getTextFiles().iterator().next().getContents().toString();
			String separator = System.getProperty("line.separator");
			if (separator == null)
				separator = "\n";
			List<GeneratedFile> files = newArrayList(access.getTextFiles());
			Collections.sort(files, new Comparator<GeneratedFile>() {
				@Override
				public int compare(GeneratedFile o1,
						GeneratedFile o2) {
					return o1.getPath().toString().compareTo(o2.getPath().toString());
				}
			});
			StringBuilder result = new StringBuilder("MULTIPLE FILES WERE GENERATED"+separator+separator);
			int i = 1;
			for (GeneratedFile file: files) {
				result.append("File "+i+" : "+file.getPath().toString()+separator+separator);
				result.append(file.getContents()).append(separator);
				i++;
			}
			return result.toString();
		}

		/**
		 * @return the resource set used in this compilation process
		 */
		public ResourceSet getResourceSet() {
			return resourceSet;
		}

		/**
		 * Convenience method for single generated Java classes
		 */
		public Class<?> getCompiledClass() {
			return IterableExtensions.head(getCompiledClasses().values());
		}
		
		/**
		 * Convenience method for single generated Java classes
		 */
		public Class<?> getCompiledClass(String className) {
			return getCompiledClasses().get(className);
		}

		/**
		 * @return all generated resources. the key is the file path and the value denotes the generated text. 
		 */
		public Map<String, CharSequence> getAllGeneratedResources() {
			doGenerate();
			Map<String,CharSequence> result = newHashMap();
			for (GeneratedFile f: access.getTextFiles()) {
				result.put(f.getPath().toString(), f.getContents());
			}
			return result;
		}
		
		protected void doIndex() {
			if (index == null) {
				// indexing
				List<IResourceDescription> descriptions = newArrayList();
				for (Resource resource : sources) {
					IResourceServiceProvider serviceProvider = serviceRegistry.getResourceServiceProvider(resource.getURI());
					IResourceDescription description = serviceProvider.getResourceDescriptionManager().getResourceDescription(resource);
					descriptions.add(description);
				}
				index = new ResourceDescriptionsData(descriptions);
				ResourceDescriptionsData.ResourceSetAdapter.installResourceDescriptionsData(resourceSet, index);
			}
		}
		
		protected void doLinking() {
			doIndex();
			for (Resource resource : sources) {
				EcoreUtil2.resolveLazyCrossReferences(resource, CancelIndicator.NullImpl);
			}
		}
		
		protected void doValidation() {
			if (allErrorsAndWarnings == null) {
				doLinking();
				allErrorsAndWarnings = newArrayList();
				// validation
				for (Resource resource : sources) {
					if (resource instanceof XtextResource) {
						XtextResource xtextResource = (XtextResource) resource;
						List<Issue> issues = xtextResource.getResourceServiceProvider().getResourceValidator().validate(xtextResource, checkMode, CancelIndicator.NullImpl);
						for (Issue issue : issues) {
							allErrorsAndWarnings.add(issue);
						}
					}
				}
			}
		}
		
		protected void doGenerate() {
			if (access == null) {
				doValidation();
				access = fileSystemAccessProvider.get();
				access.setOutputConfigurations(outputConfigurations);
				access.setCurrentSource("src");
				access.setProjectName(PROJECT_NAME);
				for (Resource resource : sources) {
					if (resource instanceof XtextResource) {
						XtextResource xtextResource = (XtextResource) resource;
						IGenerator generator = xtextResource.getResourceServiceProvider().get(IGenerator.class);
						if (generator != null) {
							try {
								elementIssueProviderFactory.attachData(xtextResource);
								generator.doGenerate(xtextResource, access);
							} finally {
								elementIssueProviderFactory.detachData(xtextResource);
							}
						}
					}
				}
				generatedCode = newHashMap();
				for (final GeneratedFile e : access.getTextFiles()) {
					if (e.getJavaClassName() != null) {
						generatedCode.put(e.getJavaClassName(), e.getContents().toString());
					}
				}
			}
		}
		
		protected void doCompile() {
			if (compiledClasses == null || classLoader==null) {
				doGenerate();
				try {
					org.eclipse.xtext.util.Pair<ClassLoader, Map<String, Class<?>>> compilationResult = javaCompiler.internalCompileToClasses(getGeneratedCode());
					this.classLoader = compilationResult.getFirst();
					this.compiledClasses = compilationResult.getSecond();
				} catch (IllegalArgumentException e) {
					throw new AssertionError(e);
				}
			}
		}
	}

}
