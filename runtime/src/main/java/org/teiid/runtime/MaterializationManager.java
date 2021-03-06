/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
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
package org.teiid.runtime;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.teiid.adminapi.VDB.Status;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.client.util.ResultsFuture;
import org.teiid.client.util.ResultsFuture.CompletionListener;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.core.util.StringUtil;
import org.teiid.deployers.CompositeVDB;
import org.teiid.deployers.ContainerLifeCycleListener;
import org.teiid.deployers.VDBLifeCycleListener;
import org.teiid.deployers.VDBRepository;
import org.teiid.dqp.internal.process.DQPCore;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.Schema;
import org.teiid.metadata.Table;
import org.teiid.query.metadata.MaterializationMetadataRepository;
import org.teiid.query.metadata.TransformationMetadata;
import org.teiid.query.tempdata.TempTableDataManager;
import org.teiid.runtime.NodeTracker.NodeListener;
import org.teiid.vdb.runtime.VDBKey;

public abstract class MaterializationManager implements VDBLifeCycleListener, NodeListener {
	
	private interface MaterializationAction {
		void process(Table table);
	}

	private static final int WAITTIME = 60000;
	public abstract Executor getExecutor();
    public abstract ScheduledExecutorService getScheduledExecutorService();
    public abstract DQPCore getDQP();
    public abstract VDBRepository getVDBRepository();
	
	private ContainerLifeCycleListener shutdownListener;
	
	public MaterializationManager (ContainerLifeCycleListener shutdownListener) {
		this.shutdownListener = shutdownListener;
	}
	
	@Override
	public void added(String name, CompositeVDB cvdb) {
	}

	@Override
	public void beforeRemove(String name, CompositeVDB cvdb) {
		if (cvdb == null) {
			return;
		}
		final VDBMetaData vdb = cvdb.getVDB();
		
        // cancel any matview load pending tasks
		Collection<Future<?>> tasks = cvdb.clearTasks();
		if (tasks != null && !tasks.isEmpty()) {
	        for (Future<?> f:tasks) {
	        	f.cancel(true);
        	}
		}
        
        // If VDB is being undeployed, run the shutdown triggers
		if (!shutdownListener.isShutdownInProgress()) {
			doMaterializationActions(vdb, new MaterializationAction() {
				
				@Override
				public void process(Table table) {
					if (table.getMaterializedTable() == null) {
						return;
					}
					String remove = table.getProperty(MaterializationMetadataRepository.ON_VDB_DROP_SCRIPT, false);
					if (remove != null) {
						try {
							executeQuery(vdb, remove);
						} catch (SQLException e) {
							LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
						}					
					}
				}
			});
		}
	}

	@Override
	public void removed(String name, CompositeVDB cvdb) {
	}

	@Override
	public void finishedDeployment(String name, final CompositeVDB cvdb) {

		// execute start triggers
		final VDBMetaData vdb = cvdb.getVDB();
		if (vdb.getStatus() != Status.ACTIVE) {
		    return;
		}
			doMaterializationActions(vdb, new MaterializationAction() {
				@Override
				public void process(final Table table) {
					if (table.getMaterializedTable() == null) {
						String ttlStr = table.getProperty(MaterializationMetadataRepository.MATVIEW_TTL, false);
						if (ttlStr != null) {
							long ttl = Long.parseLong(ttlStr);
							if (ttl > 0) {
								//TODO: make the interval based upon the state as with the external, but
								//for now just refresh on schedule
								Future<?> f = getScheduledExecutorService().scheduleAtFixedRate(new Runnable() {
									@Override
									public void run() {
										boolean invalidate = TempTableDataManager.shouldInvalidate(vdb);
										try {
											executeAsynchQuery(vdb, "call SYSADMIN.refreshMatView('" + table.getFullName().replaceAll("'", "''") + "', " + invalidate + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
										} catch (SQLException e) {
											LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
										}
									}
								}, 0, ttl, TimeUnit.MILLISECONDS);
								cvdb.addTask(f);
								return;
							}
						}
						//just a one time load
						try {
							//we use a count so that the load can cascade
							executeAsynchQuery(vdb, "select count(*) from " + table.getSQLString()); //$NON-NLS-1$
						} catch (SQLException e) {
							LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
						} 
						return;
					}

					// reset any jobs started by this node that did not complete
					String nodeName = System.getProperty("jboss.node.name"); //$NON-NLS-1$
                    resetPendingJob(vdb, table, nodeName);
					
					String start = table.getProperty(MaterializationMetadataRepository.ON_VDB_START_SCRIPT, false);
					if (start != null) {
						try {
							executeQuery(vdb, start);
						} catch (SQLException e) {
							LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
						}
					}
					
					long ttl = 0;
					String ttlStr = table.getProperty(MaterializationMetadataRepository.MATVIEW_TTL, false);
					if (ttlStr == null) {
					    ttlStr = String.valueOf(Long.MAX_VALUE);
					}
					if (ttlStr != null) {
						ttl = Long.parseLong(ttlStr);
						if (ttl > 0) {
							scheduleSnapshotJob(cvdb, table, ttl, 0L, false);
						}
					}
					
					String stalenessString = table.getProperty(MaterializationMetadataRepository.MATVIEW_MAX_STALENESS_PCT, false);
					
					if (stalenessString != null) {
					    // run first time like, SNAPSHOT
					    if (ttl <= 0) {
					        scheduleSnapshotJob(cvdb, table, 0L, 0L, true);
					    }

					    // schedule a check every minute 
					    scheduleSnapshotJob(cvdb, table, WAITTIME, WAITTIME, true);
					}
				}
			});
	}
	
    public int resetPendingJob(final VDBMetaData vdb, final Table table, String nodeName){
        try {
            String statusTable = table.getProperty(MaterializationMetadataRepository.MATVIEW_STATUS_TABLE, false);
            String updateStatusTable = "UPDATE "+statusTable+" SET LOADSTATE='needs_loading' "
                    + "WHERE LOADSTATE = 'LOADING' AND NODENAME = '"+nodeName+"' "
                    + "AND NAME = '"+table.getName()+"'";
            List<Map<String, String>> results = executeQuery(vdb, updateStatusTable);
            String count = results.get(0).get("update-count");
            return Integer.parseInt(count);
        } catch (SQLException e) {
            LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
        }
        return 0;
    }
    
	private void doMaterializationActions(VDBMetaData vdb, MaterializationAction action) {
		TransformationMetadata metadata = vdb.getAttachment(TransformationMetadata.class);
		if (metadata == null) {
			return;
		}
		
		Set<String> imports = vdb.getImportedModels();
		MetadataStore store = metadata.getMetadataStore();
		// schedule materialization loads and do the start actions
		for (Schema schema : store.getSchemaList()) {
			if (imports.contains(schema.getName())) {
				continue;
			}
			for (Table table:schema.getTables().values()) {
				// find external matview table
				if (!table.isVirtual() || !table.isMaterialized() 
						|| !Boolean.valueOf(table.getProperty(MaterializationMetadataRepository.ALLOW_MATVIEW_MANAGEMENT, false))) {
					continue;
				}
				action.process(table);
			}
		}
	}
	
	public void scheduleSnapshotJob(CompositeVDB vdb, Table table, long ttl, long delay, boolean oneTimeJob) {
		SnapshotJobScheduler task = new SnapshotJobScheduler(vdb, table, ttl, delay, oneTimeJob);
		queueTask(vdb, task, delay);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void runJob(final CompositeVDB vdb, final Table table, final long ttl, final boolean onetimeJob) {
		String command = "execute SYSADMIN.loadMatView('"+StringUtil.replaceAll(table.getParent().getName(), "'", "''")+"','"+StringUtil.replaceAll(table.getName(), "'", "''")+"')"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
		try {
		    final AtomicInteger procReturn = new AtomicInteger();
			executeAsynchQuery(vdb.getVDB(), command, new DQPCore.ResultsListener() {
                @Override
                public void onResults(List<String> columns, List<? extends List<?>> results) throws Exception {
                    procReturn.set((Integer)results.get(0).get(0));
                }}).addCompletionListener(new CompletionListener() {
				@Override
				public void onCompletion(ResultsFuture future) {
					try {
						future.get();
						if (!onetimeJob) {
						    if (procReturn.get() >= 0) {
						        scheduleSnapshotJob(vdb, table, ttl, ttl, onetimeJob);
						    } else {
						        // when in error re-schedule in 1 min or less
						        scheduleSnapshotJob(vdb, table, ttl, Math.min(ttl/4, WAITTIME), onetimeJob);
						    }
						}
					} catch (InterruptedException e) {
					} catch (ExecutionException e) {
						LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
					    scheduleSnapshotJob(vdb, table, ttl, Math.min(ttl/4, WAITTIME), onetimeJob); // re-schedule the same job in one minute
					}
				}
			});
		} catch (SQLException e) {
			LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
		    scheduleSnapshotJob(vdb, table, ttl, Math.min(ttl/4, WAITTIME), onetimeJob); // re-schedule the same job in one minute
		}
	}	
	
	private void queueTask(CompositeVDB vdb, SnapshotJobScheduler task, long delay) {
		ScheduledFuture<?> sf = getScheduledExecutorService().schedule(task, (delay < 0)?0:delay, TimeUnit.MILLISECONDS);
		task.attachScheduledFuture(sf);
	}
	
	interface MaterializationTask extends Runnable {
	    void attachScheduledFuture(ScheduledFuture<?> sf);
	    void removeScheduledFuture();
	}
	
	class SnapshotJobScheduler implements MaterializationTask {
		protected Table table;
		protected long ttl;
		protected long delay;
		protected CompositeVDB vdb;
		protected ScheduledFuture<?> future;
		protected boolean oneTimeJob;
		
		public SnapshotJobScheduler(CompositeVDB vdb, Table table, long ttl, long delay, boolean oneTimeJob) {
			this.vdb = vdb;
			this.table = table;
			this.ttl = ttl;
			this.delay = delay;
			this.oneTimeJob = oneTimeJob;
		}

        public void attachScheduledFuture(ScheduledFuture<?> sf) {
            vdb.addTask(sf);
            future = sf;
        }
        
        public void removeScheduledFuture() {
            vdb.removeTask(future);
            future = null;
        }
		
		@Override
		public void run() {
		    removeScheduledFuture();
			String query = "execute SYSADMIN.matViewStatus('"+StringUtil.replaceAll(table.getParent().getName(), "'", "''")+"', '"+StringUtil.replaceAll(table.getName(), "'", "''")+"')"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ 
			
			// in rare situations when VDB is force re-deployed, and is results in invalid
			// deployment, due to nature of VDB deployment even previous VDB is removed. 
			if (vdb.getVDB().getStatus() != Status.ACTIVE) {
			    return;
			}
			
			// when source or target data source(s) are down, there is no reason
			// to run the materialization jobs. schedule for later time
            if (!vdb.getVDB().isValid()) {
                scheduleSnapshotJob(vdb, table, ttl, Math.min(ttl/4, WAITTIME), oneTimeJob); // re-schedule the same job in one minute
                return;
            }			
			
			List<Map<String, String>> result = null;
			try {
				result = executeQuery(vdb.getVDB(), query);
			} catch (SQLException e) {
				LogManager.logWarning(LogConstants.CTX_MATVIEWS, e, e.getMessage());
			    scheduleSnapshotJob(vdb, table, ttl, Math.min(ttl/4, WAITTIME), oneTimeJob); // re-schedule the same job in one minute
				return;
			}
			
			long updated = 0L;
			this.delay = ttl;
			String loadstate = null;
			boolean valid = false;
			if (result != null && !result.isEmpty()) {
				Map<String, String> row = result.get(0);
				if (row != null) {
					loadstate = row.get("LoadState"); //$NON-NLS-1$
					updated = Long.parseLong(row.get("Updated")); //$NON-NLS-1$
					valid = Boolean.parseBoolean(row.get("Valid")); //$NON-NLS-1$
				}
			}
			
			long elapsed = System.currentTimeMillis() - updated;
			long next = ttl;
			if (loadstate == null || loadstate.equalsIgnoreCase("needs_loading") || !valid) { //$NON-NLS-1$
				// no entry found run immediately
				runJob(vdb, table, ttl, oneTimeJob);
				return;
			}
			else if (loadstate.equalsIgnoreCase("loading")) { //$NON-NLS-1$
				// if the process is already loading do nothing
				next = ttl - elapsed;
                if (elapsed >= ttl) {
                    next = Math.min(ttl / 4, 60000);
                }
			}
			else if (loadstate.equalsIgnoreCase("loaded")) { //$NON-NLS-1$
				if (elapsed >= ttl) {
					runJob(vdb, table, ttl, oneTimeJob);
					return;
				} 
				next = ttl - elapsed;	
			}
			else if (loadstate.equalsIgnoreCase("failed_load")) { //$NON-NLS-1$
				if (elapsed > ttl/4 || elapsed > 60000) { // exceeds 1/4 of cached time or 1 mins
					runJob(vdb, table, ttl, oneTimeJob);
					return;
				}
				next = Math.min(((ttl/4)-elapsed), (60000-elapsed));
			}
			scheduleSnapshotJob(vdb, table, ttl, next, oneTimeJob);
		}
	}	
	
	public ResultsFuture<?> executeAsynchQuery(VDBMetaData vdb, String command) throws SQLException {
		try {
			return DQPCore.executeQuery(command, vdb, "embedded-async", "internal", -1, getDQP(), new DQPCore.ResultsListener() { //$NON-NLS-1$ //$NON-NLS-2$
				@Override
				public void onResults(List<String> columns,
						List<? extends List<?>> results) throws Exception {
				}
			});
		} catch (Throwable e) {
			throw new SQLException(e);
		}
	}
	
    public ResultsFuture<?> executeAsynchQuery(VDBMetaData vdb, String command, DQPCore.ResultsListener listener) throws SQLException {
        try {
            return DQPCore.executeQuery(command, vdb, "embedded-async", "internal", -1, getDQP(), listener); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }	
    public List<Map<String, String>> executeQuery(VDBMetaData vdb, String command) throws SQLException {
        final List<Map<String, String>> rows = new ArrayList<Map<String,String>>();
        try {
        	Future<?> f = DQPCore.executeQuery(command, vdb, "embedded-async", "internal", -1, getDQP(), new DQPCore.ResultsListener() { //$NON-NLS-1$ //$NON-NLS-2$
                @Override
                public void onResults(List<String> columns, List<? extends List<?>> results) throws Exception {
                    for (List<?> row:results) {
                        TreeMap<String, String> rowResult = new TreeMap<String, String>();
                        for (int colNum = 0; colNum < columns.size(); colNum++) {
                            Object value = row.get(colNum);
                            if (value != null) {
                                if (value instanceof Timestamp) {
                                    value = ((Timestamp)value).getTime();
                                }
                            }
                            rowResult.put(columns.get(colNum), value == null?null:value.toString());
                        }
                        rows.add(rowResult);
                    }
                }
            }); 
        	f.get();
        } catch (InterruptedException e) {
        	//break
        	throw new TeiidRuntimeException(e); 
        } catch (ExecutionException e) {
        	if (e.getCause() != null) {
                throw new SQLException(e.getCause()); 
        	}
        	throw new SQLException(e);
        } catch (Throwable e) {
            throw new SQLException(e);
        }   
        return rows;
    }
    
    @Override
    public void nodeJoined(String nodeName) {
        // may be nothing to do for now, we can envision replicating cache pro actively.
    }

    @Override
    public void nodeDropped(String nodeName) {
        for (VDBMetaData vdb:getVDBRepository().getVDBs()) {
            TransformationMetadata metadata = vdb.getAttachment(TransformationMetadata.class);
            if (metadata == null) {
                continue;
            }
            
            for (ModelMetaData model : vdb.getModelMetaDatas().values()) {
                if (vdb.getImportedModels().contains(model.getName())) {
                    continue;
                }
                
                MetadataStore store = metadata.getMetadataStore();
                Schema schema = store.getSchema(model.getName()); 
                for (Table t:schema.getTables().values()) {
                    if (t.isVirtual() && t.isMaterialized() && t.getMaterializedTable() != null) {
                        String allow = t.getProperty(MaterializationMetadataRepository.ALLOW_MATVIEW_MANAGEMENT, false);
                        if (allow == null || !Boolean.valueOf(allow)) {
                            continue;
                        }
                        // reset the pending job if there is one.
                        int update = resetPendingJob(vdb, t, nodeName);
                        if (update > 0) {
                            String ttlStr = t.getProperty(MaterializationMetadataRepository.MATVIEW_TTL, false);
                            if (ttlStr == null) {
                                ttlStr = String.valueOf(Long.MAX_VALUE);
                            }
                            if (ttlStr != null) {
                                long ttl = Long.parseLong(ttlStr);
                                if (ttl > 0) {
                                    // run the job
                                    CompositeVDB cvdb = getVDBRepository().getCompositeVDB(new VDBKey(vdb.getName(), vdb.getVersion()));
                                    scheduleSnapshotJob(cvdb, t, ttl, 0L, true);
                                }
                            }
                        }
                    }
                }
            }
        }
    }    
}
