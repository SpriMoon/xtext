/*******************************************************************************
 * Copyright (c) 2009 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.builder.impl;

import java.lang.reflect.Method;
import java.util.Map;

import org.apache.log4j.Logger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.builder.IXtextBuilderParticipant.BuildType;
import org.eclipse.xtext.builder.builderState.IBuilderState;
import org.eclipse.xtext.builder.debug.IBuildLogger;
import org.eclipse.xtext.resource.IResourceDescription.Delta;
import org.eclipse.xtext.resource.impl.ResourceDescriptionsProvider;
import org.eclipse.xtext.service.OperationCanceledError;
import org.eclipse.xtext.service.OperationCanceledManager;
import org.eclipse.xtext.ui.XtextProjectHelper;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.internal.Stopwatches;
import org.eclipse.xtext.util.internal.Stopwatches.StoppedTask;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

/**
 * @author Sven Efftinge - Initial contribution and API
 * @author Jan Koehnlein
 * @author Knut Wannheden
 * @author Sebastian Zarnekow - BuildData as blackboard for scheduled data
 */
public class XtextBuilder extends IncrementalProjectBuilder {

	private static final Logger log = Logger.getLogger(XtextBuilder.class);

	public static final String BUILDER_ID = XtextProjectHelper.BUILDER_ID;

	@Inject
	private ToBeBuiltComputer toBeBuiltComputer;

	@Inject
	private IBuilderState builderState;

	@Inject
	private IResourceSetProvider resourceSetProvider;

	@Inject
	private RegistryBuilderParticipant participant;

	@Inject
	private QueuedBuildData queuedBuildData;

	@Inject
	private IBuildLogger buildLogger;
	
	@Inject 
	private OperationCanceledManager operationCanceledManager;
	
	public IResourceSetProvider getResourceSetProvider() {
		return resourceSetProvider;
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected IProject[] build(final int kind, Map args, IProgressMonitor monitor) throws CoreException {
		if (IBuildFlag.FORGET_BUILD_STATE_ONLY.isSet(args)) {
			forgetLastBuiltState();
			return getProject().getReferencedProjects();
		}
		long startTime = System.currentTimeMillis();
		StoppedTask task = Stopwatches.forTask(String.format("XtextBuilder.build[%s]", getKindAsString(kind)));
		try {
			queuedBuildData.createCheckpoint();
			if(shouldCancelBuild(kind)) {
				throw new OperationCanceledException("Build has been interrupted");
			}
			task.start();
			if (monitor != null) {
				final String taskName = Messages.XtextBuilder_Building + getProject().getName() + ": "; //$NON-NLS-1$
				monitor = new ProgressMonitorWrapper(monitor) {
					@Override
					public void subTask(String name) {
						super.subTask(taskName + name);
					}
					
					@Override
					public boolean isCanceled() {
						boolean shouldCancelBuild = shouldCancelBuild(kind);
						if (shouldCancelBuild)
							buildLogger.log("interupted");
						return shouldCancelBuild || super.isCanceled();
					}
				};
			}
			SubMonitor progress = SubMonitor.convert(monitor, 1);
			if (kind == FULL_BUILD) {
				fullBuild(progress.newChild(1), IBuildFlag.RECOVERY_BUILD.isSet(args));
			} else {
				IResourceDelta delta = getDelta(getProject());
				if (delta == null || isOpened(delta)) {
					fullBuild(progress.newChild(1), IBuildFlag.RECOVERY_BUILD.isSet(args));
				} else {
					incrementalBuild(delta, progress.newChild(1));
				}
			}
		} catch (CoreException e) {
			log.error(e.getMessage(), e);
			throw e;
		} catch (OperationCanceledException e) {
			handleCanceled(e);
		} catch (OperationCanceledError err) {
			handleCanceled(err);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			buildLogger.log(e.getClass().getSimpleName() + " while building " + getProject().getName() + ": " + e.getMessage() + " (see logs for details)");
			forgetLastBuiltState();
		} finally {
			queuedBuildData.discardCheckpoint();
			if (monitor != null)
				monitor.done();
			String message = "Build " + getProject().getName() + " in " + (System.currentTimeMillis() - startTime) + " ms";
			log.info(message);
			buildLogger.log(message);
			task.stop();
		}
		return getProject().getReferencedProjects();
	}

	private boolean shouldCancelBuild(int buildKind) {
		return buildKind == IncrementalProjectBuilder.AUTO_BUILD && isInterrupted();
	}

	private void handleCanceled(Throwable t) {
		// If the cancelation happens due to an external interruption, don't pass an 
		// OperationCanceledException on to the BuildManager as it would force a full 
		// build in the next round. Instead, save the resource deltas to be reprocessed 
		// next time.
		// @see https://bugs.eclipse.org/bugs/show_bug.cgi?id=454716
		if(!isInterrupted())
			operationCanceledManager.propagateIfCancelException(t);
		buildLogger.log("Build interrupted.");
		queuedBuildData.rollback();
		doRememberLastBuiltState();
	}
	
	private void doRememberLastBuiltState() {
		try {
			Method method = getClass().getMethod("rememberLastBuiltState");
			method.invoke(this);
		} catch (Exception e) {
			// not available prior to Eclipse 3.7. Sorry: full build next time
			throw new OperationCanceledException();
		}
	}
	
	private String getKindAsString(int kind) {
		if (kind == FULL_BUILD) {
			return "FULL";
		}
		if (kind == CLEAN_BUILD) {
			return "CLEAN";
		}
		if (kind == INCREMENTAL_BUILD) {
			return "INCREMENTAL";
		}
		if (kind == AUTO_BUILD) {
			return "AUTO";
		}
		return "UNKOWN:" + kind;
	}

	/**
	 * @param monitor the progress monitor to use for reporting progress to the user. It is the caller's responsibility
	 *        to call done() on the given monitor. Accepts null, indicating that no progress should be
	 *        reported and that the operation cannot be cancelled.
	 */
	protected void incrementalBuild(IResourceDelta delta, final IProgressMonitor monitor) throws CoreException {
		final SubMonitor progress = SubMonitor.convert(monitor, Messages.XtextBuilder_CollectingResources, 10);
		progress.subTask(Messages.XtextBuilder_CollectingResources);
		
		if (queuedBuildData.needRebuild(getProject())) {
			needRebuild();
		}

		final ToBeBuilt toBeBuilt = new ToBeBuilt();
		IResourceDeltaVisitor visitor = new IResourceDeltaVisitor() {
			@Override
			public boolean visit(IResourceDelta delta) throws CoreException {
				if (progress.isCanceled())
					throw new OperationCanceledException();
				if (delta.getResource() instanceof IProject) {
					return delta.getResource() == getProject();
				}
				if (delta.getResource() instanceof IStorage) {
					if (delta.getKind() == IResourceDelta.REMOVED) {
						return toBeBuiltComputer.removeStorage(null, toBeBuilt, (IStorage) delta.getResource());
					} else if (delta.getKind() == IResourceDelta.ADDED || delta.getKind() == IResourceDelta.CHANGED) {
						return toBeBuiltComputer.updateStorage(null, toBeBuilt, (IStorage) delta.getResource());
					}
				}
				return true;
			}
		};
		delta.accept(visitor);
		if (progress.isCanceled())
			throw new OperationCanceledException();
		progress.worked(2);
		doBuild(toBeBuilt, progress.newChild(8), BuildType.INCREMENTAL);
	}

	/**
	 * @param monitor the progress monitor to use for reporting progress to the user. It is the caller's responsibility
	 *        to call done() on the given monitor. Accepts null, indicating that no progress should be
	 *        reported and that the operation cannot be cancelled.
	 */
	protected void doBuild(ToBeBuilt toBeBuilt, IProgressMonitor monitor, BuildType type) throws CoreException {
		buildLogger.log("Building " + getProject().getName());
		// return early if there's nothing to do.
		// we reuse the isEmpty() impl from BuildData assuming that it doesnT access the ResourceSet which is still null 
		// and would be expensive to create.
		boolean indexingOnly = type == BuildType.RECOVERY;
		if (new BuildData(getProject().getName(), null, toBeBuilt, queuedBuildData, indexingOnly).isEmpty())
			return;
		SubMonitor progress = SubMonitor.convert(monitor, 2);
		ResourceSet resourceSet = getResourceSetProvider().get(getProject());
		resourceSet.getLoadOptions().put(ResourceDescriptionsProvider.NAMED_BUILDER_SCOPE, Boolean.TRUE);
		BuildData buildData = new BuildData(getProject().getName(), resourceSet, toBeBuilt, queuedBuildData, indexingOnly);
		ImmutableList<Delta> deltas = builderState.update(buildData, progress.newChild(1));
		if (participant != null && !indexingOnly) {
			participant.build(new BuildContext(this, resourceSet, deltas, type),
					progress.newChild(1));
			try {
				getProject().getWorkspace().checkpoint(false);
			} catch(NoClassDefFoundError e) { // guard against broken Eclipse installations / bogus project configuration
				log.error(e.getMessage(), e);
			}
		} else {
			progress.worked(1);
		}
		resourceSet.eSetDeliver(false);
		resourceSet.getResources().clear();
		resourceSet.eAdapters().clear();
	}

	/**
	 * @param monitor the progress monitor to use for reporting progress to the user. It is the caller's responsibility
	 *        to call done() on the given monitor. Accepts null, indicating that no progress should be
	 *        reported and that the operation cannot be cancelled.
	 */
	protected void fullBuild(final IProgressMonitor monitor, boolean isRecoveryBuild) throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor, 10);

		IProject project = getProject();
		ToBeBuilt toBeBuilt = 
			isRecoveryBuild
				? toBeBuiltComputer.updateProjectNewResourcesOnly(project, progress.newChild(2)) 
				: toBeBuiltComputer.updateProject(project, progress.newChild(2));
		doBuild(toBeBuilt, progress.newChild(8), 
			isRecoveryBuild 
				? BuildType.RECOVERY 
				: BuildType.FULL);
	}

	protected boolean isOpened(IResourceDelta delta) {
		return delta.getResource() instanceof IProject 
				&& (delta.getFlags() & IResourceDelta.OPEN) != 0
				&& ((IProject) delta.getResource()).isOpen();
	}

	/**
	 * @param monitor the progress monitor to use for reporting progress to the user. It is the <em>implementors</em> responsibility
	 *        to call done() on the given monitor. Accepts null, indicating that no progress should be
	 *        reported and that the operation cannot be cancelled.
	 */
	@Override
	protected void clean(IProgressMonitor monitor) throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor, 10);
		try {
			ToBeBuilt toBeBuilt = toBeBuiltComputer.removeProject(getProject(), progress.newChild(2));
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			doClean(toBeBuilt, progress.newChild(8));
		} finally {
			if (monitor != null)
				monitor.done();
		}
	}

	/**
	 * @param monitor the progress monitor to use for reporting progress to the user. It is the caller's responsibility
	 *        to call done() on the given monitor. Accepts null, indicating that no progress should be
	 *        reported and that the operation cannot be cancelled.
	 */
	protected void doClean(ToBeBuilt toBeBuilt, IProgressMonitor monitor) throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor, 2);
		ImmutableList<Delta> deltas = builderState.clean(toBeBuilt.getToBeDeleted(), progress.newChild(1));
		if (participant != null) {
			participant.build(new BuildContext(this, 
					getResourceSetProvider().get(getProject()), 
					deltas,
					BuildType.CLEAN), 
					progress.newChild(1));
		} else {
			progress.worked(1);
		}
	}
	
}
