/*******************************************************************************
 * Copyright (c) 2015, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package melnorme.lang.ide.core.engine;

import static melnorme.utilbox.core.Assert.AssertNamespace.assertNotNull;
import static melnorme.utilbox.core.Assert.AssertNamespace.assertTrue;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;

import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.utils.operation.OperationUtils;
import melnorme.lang.tooling.structure.SourceFileStructure;
import melnorme.lang.utils.concurrency.ConcurrentlyDerivedData;
import melnorme.lang.utils.concurrency.ConcurrentlyDerivedData.DataUpdateTask;
import melnorme.lang.utils.concurrency.SynchronizedEntryMap;
import melnorme.utilbox.concurrency.OperationCancellation;
import melnorme.utilbox.fields.ListenerListHelper;
import melnorme.utilbox.misc.Location;
import melnorme.utilbox.ownership.StrictDisposable;

/**
 * The SourceModelManager keeps track of text document changes, and updates derived models, such as:
 * 
 * - Source module parse-analysis and structure-info (possibly with problem markers update).
 * - Possible persist document changes to files in filesystem, or update a semantic engine buffers.
 *
 */
public abstract class SourceModelManager extends AbstractModelUpdateManager<Object> {
	
	public SourceModelManager() {
		this(new ProblemMarkerUpdater());
	}
	
	public SourceModelManager(ProblemMarkerUpdater problemUpdater) {
		if(problemUpdater != null) {
			problemUpdater.install(this);
		}
	}
	
	/* -----------------  ----------------- */
	
	protected final SynchronizedEntryMap<Object, StructureInfo> infosMap = 
			new SynchronizedEntryMap<Object, StructureInfo>() {
		@Override
		protected StructureInfo createEntry(Object key) {
			return new StructureInfo(key);
		}
	};
	
	/**  @return the {@link SourceFileStructure} currently stored under given key. Can be null. */
	public StructureInfo getStoredStructureInfo(Object key) {
		return infosMap.getEntryOrNull(key);
	}
	
	/**
	 * Connect given structure listener to structure under given key, 
	 * and begin structure updates using given document as input.
	 * 
	 * If a previous listener was already connected, but with a different document,
	 * an unmanaged {@link StructureInfo} will be returned
	 * 
	 * @return non-null. The {@link StructureInfo} resulting from given connection.
	 */
	public StructureModelRegistration connectStructureUpdates(Object key, IDocument document, 
			IStructureModelListener structureListener) {
		assertNotNull(key);
		assertNotNull(document);
		assertNotNull(structureListener);
		log.println("connectStructureUpdates: " + key);
		
		StructureInfo sourceInfo = infosMap.getEntry(key);
		
		boolean connected = sourceInfo.connectDocument(document, structureListener);
		
		if(!connected) {
			// Special case: this key has already been connected to, but with a different document.
			// As such, return a unmanaged StructureInfo
			sourceInfo = new StructureInfo(key);
			connected = sourceInfo.connectDocument(document, structureListener);
		}
		assertTrue(connected);
		
		return new StructureModelRegistration(sourceInfo, structureListener);
	}
	
	public class StructureModelRegistration extends StrictDisposable {
		
		public final StructureInfo structureInfo;
		protected final IStructureModelListener structureListener;
		
		public StructureModelRegistration(StructureInfo structureInfo, IStructureModelListener structureListener) {
			this.structureInfo = assertNotNull(structureInfo);
			this.structureListener = assertNotNull(structureListener);
		}
		
		@Override
		protected void disposeDo() {
			log.println("disconnectStructureUpdates: " + structureInfo.getKey());
			structureInfo.disconnectFromDocument(structureListener);
		}
		
	}
	
	/* -----------------  ----------------- */
	
	public class StructureInfo extends ConcurrentlyDerivedData<SourceFileStructure, StructureInfo> {
		
		protected final Object key;
		protected final StructureUpdateTask disconnectTask; // Can be null
		
		protected IDocument document = null;
		
		public StructureInfo(Object key) {
			this.key = assertNotNull(key);
			
			this.disconnectTask = assertNotNull(createDisconnectTask(this));
		}
		
		public final Object getKey() {
			return key;
		}
		
		/**
		 * @return the file location if source is based on a file, null otherwise.
		 */
		public Location getLocation() {
			if(key instanceof Location) {
				return (Location) key;
			}
			return null;
		}
		
		public synchronized boolean hasConnectedListeners() {
			return connectedListeners.getListeners().size() > 0;
		}
		
		protected synchronized boolean connectDocument(IDocument newDocument, IStructureModelListener listener) {
			if(document == null) {
				document = newDocument;
				newDocument.addDocumentListener(docListener);
				queueSourceUpdateTask(document.get());
			}
			else if(document != newDocument) {
				return false;
			}
			
			connectedListeners.addListener(listener);
			return true;
		}
		
		protected final IDocumentListener docListener = new IDocumentListener() {
			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}
			@Override
			public void documentChanged(DocumentEvent event) {
				queueSourceUpdateTask(document.get());
			}
		};
		
		protected synchronized void disconnectFromDocument(IStructureModelListener structureListener) {
			connectedListeners.removeListener(structureListener);
			
			if(!hasConnectedListeners()) {
				document.removeDocumentListener(docListener);
				document = null;
				
				queueUpdateTask(disconnectTask);
			}
		}
		
		protected void queueSourceUpdateTask(final String source) {
			StructureUpdateTask updateTask = createUpdateTask(this, source);
			queueUpdateTask(updateTask);
		}
		
		protected synchronized void queueUpdateTask(StructureUpdateTask updateTask) {
			setUpdateTask(updateTask);
			
			executor.submit(updateTask);
		}
		
		@Override
		protected void doHandleDataUpdateRequested() {
			for(IDataUpdateRequestedListener<StructureInfo> listener : updateRequestedListeners.getListeners()) {
				listener.dataUpdateRequested(this);
			}
		}
		
		@Override
		protected void doHandleDataChanged() {
			notifyStructureChanged(this, connectedListeners);
			notifyStructureChanged(this, globalListeners);
			
			if(!hasConnectedListeners()) {
				// TODO need to verify thread-safety, to enable this code.
				assertTrue(getStoredData() == null);
//				infosMap.runSynchronized(() -> infosMap.removeEntry(key));
			}
		}
		
		public SourceFileStructure awaitUpdatedData(IProgressMonitor pm) throws OperationCancellation {
			return OperationUtils.awaitData(asFuture(), pm);
		}
		
	}
	
	/* -----------------  ----------------- */
	
	/**
	 * Create an update task for the given structureInfo, due to a document change.
	 * @param source the new source of the document being listened to.
	 */
	protected abstract StructureUpdateTask createUpdateTask(StructureInfo structureInfo, String source);
	
	/**
	 * Create an update task for when the last listener of given structureInfo disconnects.
	 */
	protected DisconnectUpdatesTask createDisconnectTask(StructureInfo structureInfo) {
		return new DisconnectUpdatesTask(structureInfo);
	}
	
	public static abstract class StructureUpdateTask extends DataUpdateTask<SourceFileStructure> {
		
		protected final StructureInfo structureInfo;
		
		public StructureUpdateTask(StructureInfo structureInfo) {
			super(structureInfo, structureInfo.getKey().toString());
			this.structureInfo = structureInfo;
		}
		
		@Override
		protected void handleRuntimeException(RuntimeException e) {
			LangCore.logInternalError(e);
		}
		
	}
	
	public static class DisconnectUpdatesTask extends StructureUpdateTask {
		
		public DisconnectUpdatesTask(StructureInfo structureInfo) {
			super(structureInfo);
		}
		
		@Override
		protected SourceFileStructure createNewData() {
			Location location = structureInfo.getLocation();
			if(location != null) {
				handleDisconnectForLocation(location);
			} else {
				handleDisconnectForNoLocation();
			}
			
			return null;
		}
		
		@SuppressWarnings("unused")
		protected void handleDisconnectForLocation(Location location) {
		}
		
		//@SuppressWarnings("unused")
		protected void handleDisconnectForNoLocation() {
		}
		
	}
	
	
	/* ----------------- Listeners ----------------- */
	
	protected final ListenerListHelper<IStructureModelListener> globalListeners = new ListenerListHelper<>();
	
	public void addListener(IStructureModelListener listener) {
		assertNotNull(listener);
		globalListeners.addListener(listener);
	}
	
	public void removeListener(IStructureModelListener listener) {
		globalListeners.removeListener(listener);
	}
	
}