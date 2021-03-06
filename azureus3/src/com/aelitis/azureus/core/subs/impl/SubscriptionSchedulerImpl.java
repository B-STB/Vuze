/*
 * Created on Aug 6, 2008
 * Created by Paul Gardner
 * 
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */


package com.aelitis.azureus.core.subs.impl;

import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

import org.gudy.azureus2.core3.torrent.TOTorrentException;
import org.gudy.azureus2.core3.torrent.TOTorrentFactory;
import org.gudy.azureus2.core3.util.AERunnable;
import org.gudy.azureus2.core3.util.AESemaphore;
import org.gudy.azureus2.core3.util.AEThread2;
import org.gudy.azureus2.core3.util.AsyncDispatcher;
import org.gudy.azureus2.core3.util.Debug;
import org.gudy.azureus2.core3.util.SimpleTimer;
import org.gudy.azureus2.core3.util.SystemTime;
import org.gudy.azureus2.core3.util.TimerEvent;
import org.gudy.azureus2.core3.util.TimerEventPerformer;
import org.gudy.azureus2.core3.util.TorrentUtils;
import org.gudy.azureus2.core3.util.UrlUtils;
import org.gudy.azureus2.plugins.download.Download;
import org.gudy.azureus2.plugins.download.DownloadManager;
import org.gudy.azureus2.plugins.torrent.Torrent;
import org.gudy.azureus2.plugins.utils.DelayedTask;
import org.gudy.azureus2.plugins.utils.StaticUtilities;
import org.gudy.azureus2.plugins.utils.resourcedownloader.ResourceDownloader;
import org.gudy.azureus2.plugins.utils.resourcedownloader.ResourceDownloaderFactory;
import org.gudy.azureus2.plugins.utils.search.SearchProvider;
import org.gudy.azureus2.pluginsimpl.local.PluginInitializer;
import org.gudy.azureus2.pluginsimpl.local.torrent.TorrentImpl;
import org.gudy.azureus2.pluginsimpl.local.utils.UtilitiesImpl;

import com.aelitis.azureus.core.proxy.AEProxyFactory;
import com.aelitis.azureus.core.proxy.AEProxyFactory.PluginProxy;
import com.aelitis.azureus.core.subs.Subscription;
import com.aelitis.azureus.core.subs.SubscriptionDownloadListener;
import com.aelitis.azureus.core.subs.SubscriptionException;
import com.aelitis.azureus.core.subs.SubscriptionHistory;
import com.aelitis.azureus.core.subs.SubscriptionManagerListener;
import com.aelitis.azureus.core.subs.SubscriptionResult;
import com.aelitis.azureus.core.subs.SubscriptionScheduler;
import com.aelitis.azureus.core.cnetwork.ContentNetwork;
import com.aelitis.azureus.core.cnetwork.ContentNetworkManagerFactory;
import com.aelitis.azureus.core.metasearch.Engine;
import com.aelitis.azureus.core.metasearch.impl.web.WebEngine;
import com.aelitis.azureus.util.ConstantsVuze;
import com.aelitis.azureus.util.UrlFilter;

public class 
SubscriptionSchedulerImpl 
	implements SubscriptionScheduler, SubscriptionManagerListener
{
	private static final Object			SCHEDULER_NEXT_SCAN_KEY 			= new Object();
	private static final Object			SCHEDULER_FAILED_SCAN_CONSEC_KEY 	= new Object();
	private static final Object			SCHEDULER_FAILED_SCAN_TIME_KEY 		= new Object();
	
	private static final int			FAIL_INIT_DELAY		= 10*60*1000;
	private static final int			FAIL_MAX_DELAY		= 8*60*60*1000;
	
	private SubscriptionManagerImpl		manager;
	
	private Map	active_subscription_downloaders = new HashMap();
	private boolean active_subs_download_is_auto;
	
	private Map<String,Long>	rate_limit_map = new HashMap<String, Long>();
	
	private Set	active_result_downloaders		= new HashSet();
	
	private AsyncDispatcher	result_downloader = new AsyncDispatcher();

	private boolean		schedulng_permitted;
	
	private TimerEvent	schedule_event;
	private boolean		schedule_in_progress;
	private long		last_schedule;
	
	
	protected
	SubscriptionSchedulerImpl(
		SubscriptionManagerImpl		_manager )
	{
		manager	= _manager;
		
		manager.addListener( this );
		
		DelayedTask delayed_task = UtilitiesImpl.addDelayedTask( "Subscriptions Scheduler", 
			new Runnable()
			{
				public void
				run()
				{
					synchronized( SubscriptionSchedulerImpl.this ){
						
						schedulng_permitted	= true;
					}
					
					calculateSchedule();
				}
			});
		
		delayed_task.queue();
	}
	
	public void 
	downloadAsync(
		Subscription 	subs, 
		boolean 		is_auto )
	
		throws SubscriptionException 
	{
		download(
			subs,
			is_auto,
			new SubscriptionDownloadListener()
			{
				public void
				complete(
					Subscription		subs )
				{
				}
				
				public void
				failed(
					Subscription			subs,
					SubscriptionException	error )
				{
					log( "Async download of " + subs.getName() + " failed", error );
				}
			});
	}
	
	public void 
	download(
		final Subscription 					subs,
		final boolean						is_auto,
		final SubscriptionDownloadListener 	listener )
	{
		new AEThread2( "SS:download", true )
		{
			public void
			run()
			{
				try{
					download( subs, is_auto );
					
					listener.complete( subs );
					
				}catch( SubscriptionException e ){
					
					listener.failed( subs, e );
					
				}catch( Throwable e ){
					
					listener.failed( subs, new SubscriptionException( "Download failed", e ));
				}
			}
		}.start();
	}
	
	public boolean 
	download(
		Subscription 	subs,
		boolean			is_auto )
	
		throws SubscriptionException 
	{
		SubscriptionDownloader downloader;
		
		AESemaphore	sem = null;
		
		String rate_limits = manager.getRateLimits().trim();

		synchronized( active_subscription_downloaders ){
			
			if ( rate_limits.length() > 0 ){
				
				try{
					Engine engine = subs.getEngine();
					
					if ( engine instanceof WebEngine ){
						
						String url_str = ((WebEngine)engine).getSearchUrl( true );
						
						String host = new URL( url_str ).getHost();
						
						String[] bits = rate_limits.split( "," );
						
						for ( String bit: bits ){
							
							String[] temp = bit.trim().split( "=" );
							
							if ( temp.length == 2 ){
								
								String 	lhs = temp[0].trim();
								
								if ( lhs.equals( host )){
									
									int mins = Integer.parseInt( temp[1].trim());
									
									if ( mins > 0 ){
										
										long	now = SystemTime.getMonotonousTime();
										
										Long last = rate_limit_map.get( host );
										
										if ( last != null && now - last < mins*60*1000 ){
											
											if ( is_auto ){
											
												return( false );
											
											}else{
											
												throw( new SubscriptionException( "Rate limiting prevents download from " + host ));
											}
										}
										
										rate_limit_map.put( host, now );
									}
								}
							}
						}
					}	
				}catch( SubscriptionException e ){
					
					throw( e );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
			List	waiting = (List)active_subscription_downloaders.get( subs );
			
			if ( waiting != null ){
				
				sem = new AESemaphore( "SS:waiter" );
				
				waiting.add( sem );
				
				if ( !is_auto ){
					
					active_subs_download_is_auto = false;
				}
			}else{
							
				active_subscription_downloaders.put( subs, new ArrayList());
				
				active_subs_download_is_auto = is_auto;
			}
	
			downloader = new SubscriptionDownloader(manager, (SubscriptionImpl)subs );
		}
		
		try{
			if ( sem == null ){
			
				downloader.download();
				
			}else{
				
				sem.reserve();
			}
			
			return( true );
			
		}finally{
			
			boolean	was_auto;
			
			synchronized( active_subscription_downloaders ){

				List waiting = (List)active_subscription_downloaders.remove( subs );
				
				if ( waiting != null ){
					
					for (int i=0;i<waiting.size();i++){
						
						((AESemaphore)waiting.get(i)).release();
					}
				}
				
				was_auto = active_subs_download_is_auto;
			}
			
			((SubscriptionImpl)subs).fireDownloaded( was_auto );
		}
	}
	
	public void
	download(
		final Subscription			subs,
		final SubscriptionResult	result )
	{
		String download_link = result.getDownloadLink();
		
		if ( download_link == null ){
			
			log( subs.getName() + ": can't download " + result.getID() + " as no direct download link available" );
			
			return;
		}

		if ( UrlFilter.getInstance().isWhitelisted( download_link )){
			
			ContentNetwork cn = ContentNetworkManagerFactory.getSingleton().getContentNetworkForURL( download_link );
			
			if ( cn == null ){
				
				cn = ConstantsVuze.getDefaultContentNetwork();
			}
			
			download_link = cn.appendURLSuffix( download_link, false, true );
		}
		
		final String	key = subs.getID() + ":" + result.getID();
		final String	dl	= download_link;
		
		synchronized( active_result_downloaders ){

			if ( active_result_downloaders.contains( key )){
				
				return;
			}
		
			log( subs.getName() + ": queued result for download - " + result.getID() + "/" + download_link );
			
			active_result_downloaders.add( key );
			
			result_downloader.dispatch(
				new AERunnable()
				{
					public void 
					runSupport() 
					{
						try{
							boolean	retry = true;
						
							boolean	use_ref			= subs.getHistory().getDownloadWithReferer();
							
							boolean tried_ref_switch = false;
							
							while( retry ){
								
								retry = false;
							
								try{
									TorrentUtils.setTLSDescription( "Subscription: " + subs.getName());

									URL original_url = new URL(dl);
													
									PluginProxy plugin_proxy 	= null;
									URL			current_url 	= original_url;
									
									Torrent torrent = null;
									
									try{

										while( true ){
											
											try{
												ResourceDownloaderFactory rdf = StaticUtilities.getResourceDownloaderFactory();
												
												ResourceDownloader url_rd = rdf.create( current_url, plugin_proxy==null?null:plugin_proxy.getProxy());
												
												if ( plugin_proxy != null ){
													
													url_rd.setProperty( "URL_HOST", original_url.getHost());
												}
																										
												String referer = use_ref?subs.getReferer():null;
												
												UrlUtils.setBrowserHeaders( url_rd, referer );
												
												Engine engine = subs.getEngine();
												
												if ( engine instanceof WebEngine ){
													
													WebEngine we = (WebEngine)engine;
													
													if ( we.isNeedsAuth()){
														
														String cookies = we.getCookies();
														
														if ( cookies != null && cookies.length() > 0 ){
															
															url_rd.setProperty( "URL_Cookie", cookies );
														}
													}
												}
												
												ResourceDownloader mr_rd = rdf.getMetaRefreshDownloader( url_rd );
					
												InputStream is = mr_rd.download();
					
												torrent = new TorrentImpl( TOTorrentFactory.deserialiseFromBEncodedInputStream( is ));
												
												break;
												
											}catch( Throwable e ){
												
												if ( plugin_proxy == null ){
													
													plugin_proxy = AEProxyFactory.getPluginProxy( "Subscription result download", original_url );
												
													if ( plugin_proxy != null ){
													
														current_url = plugin_proxy.getURL();
														
														continue;
													}
												}
												
												throw( e );
											}
										}
									}finally{
										
										if ( plugin_proxy != null ){
											
											plugin_proxy.setOK( torrent != null);
										}					
										
									}
									
									// PlatformTorrentUtils.setContentTitle(torrent, torr );
							
									DownloadManager dm = PluginInitializer.getDefaultInterface().getDownloadManager();
									
									Download	download;
									
										// if we're assigning a tag then we need to add it stopped in case the tag has any pre-start actions (e.g. set initial save location)
									
									long	tag_id = subs.getTagID();
									
									boolean auto_start = manager.shouldAutoStart( torrent );
									
									if ( auto_start && tag_id < 0 ){
									
										download = dm.addDownload( torrent );
										
									}else{
									
										download = dm.addDownloadStopped( torrent, null, null );
									}
									
									log( subs.getName() + ": added download " + download.getName()+ ": auto-start=" + auto_start );

									subs.addAssociation( torrent.getHash());
									
									if ( auto_start && tag_id >= 0 ){
										
										download.restart();
									}
									
									result.setRead( true );
																		
									if ( tried_ref_switch ){
										
										subs.getHistory().setDownloadWithReferer( use_ref );
									}
								}catch( Throwable e ){
									
									log( subs.getName() + ": Failed to download result " + dl, e );
									
									if ( e instanceof TOTorrentException && !tried_ref_switch ){
										
										use_ref 			= !use_ref;
										
										tried_ref_switch	= true;
										
										retry				= true;
										
										log( subs.getName() + ": Retrying " + (use_ref?"with referer":"without referer" ));
									}
								}finally{
									
									TorrentUtils.setTLSDescription( null );
								}
							}
						}finally{
							
							synchronized( active_result_downloaders ){

								active_result_downloaders.remove( key );
							}
							
							calculateSchedule();
						}
					}
				});
		}
	}
	
	protected void
	calculateSchedule()
	{
		Subscription[]	subs = manager.getSubscriptions( true );
		
		synchronized( this ){
			
			if ( !schedulng_permitted ){
				
				return;
			}
			
			if ( schedule_in_progress ){
				
				return;
			}
			
			long	next_ready_time = Long.MAX_VALUE;
			
			Subscription	next_ready_subs = null;
			
			for (int i=0;i<subs.length;i++){
				
				Subscription sub = subs[i];
								
				SubscriptionHistory history = sub.getHistory();
				
				if ( !history.isEnabled()){
					
					continue;
				}
				
				long	next_scan = getNextScan( sub );
				
				sub.setUserData( SCHEDULER_NEXT_SCAN_KEY, new Long( next_scan ));
				
				if ( next_scan < next_ready_time ){
					
					next_ready_time = next_scan;
					
					next_ready_subs = sub;
				}
			}
		
			long	 old_when = 0;
			
			if ( schedule_event != null ){
				
				old_when = schedule_event.getWhen();
				
				schedule_event.cancel();
				
				schedule_event = null;
			}
			
			if ( next_ready_time < Long.MAX_VALUE ){
				
				long	now = SystemTime.getCurrentTime();
				
				if ( 	now < last_schedule ||
						now - last_schedule < 30*1000 ){
					
					if ( next_ready_time - now < 30*1000 ){
						
						next_ready_time = now + 30*1000;
					}
				}
					
				if ( next_ready_time < now ){
					
					next_ready_time = now;
				}
				
				log( "Calculate : " + 
						"old_time=" + new SimpleDateFormat().format(new Date(old_when)) +
						", new_time=" + new SimpleDateFormat().format(new Date(next_ready_time)) +
						", next_sub=" + next_ready_subs.getName());
						
				schedule_event = SimpleTimer.addEvent(
					"SS:Scheduler",
					next_ready_time,
					new TimerEventPerformer()
					{
						public void 
						perform(
							TimerEvent event ) 
						{
							synchronized( SubscriptionSchedulerImpl.this ){
								
								if ( schedule_in_progress ){
									
									return;
								}
								
								schedule_in_progress = true;
								
								last_schedule = SystemTime.getCurrentTime();
								
								schedule_event = null;
							}
							
							new AEThread2( "SS:Sched", true )
							{
								public void
								run()
								{
									try{
										schedule();

									}finally{
										
										synchronized( SubscriptionSchedulerImpl.this ){
											
											schedule_in_progress = false;
										}
										
										calculateSchedule();
									}
								}
							}.start();						
						}
					});
			}
		}
	}
	
	protected void
	schedule()
	{
		Subscription[]	subs = manager.getSubscriptions( true );
		
		long now = SystemTime.getCurrentTime();
		
		subs = subs.clone();
		
		synchronized( this ){

			Arrays.sort(
				subs,
				new Comparator<Subscription>()
				{
					public int 
					compare(
						Subscription s1, 
						Subscription s2) 
					{
						Long	l1 = (Long)s1.getUserData( SCHEDULER_NEXT_SCAN_KEY );
						Long	l2 = (Long)s2.getUserData( SCHEDULER_NEXT_SCAN_KEY );

						if ( l1 == l2 ){
							
							return( 0 );
							
						}else if ( l1 == null ){
							
							return( 1 );
							
						}else if ( l2 == null ){
							
							return( -1 );
							
						}else{
							
							long diff = l1 - l2;
							
							if ( diff < 0 ){
								
								return( -1 )
										;
							}else if ( diff < 0 ){
								
								return( 1 );
							}else{
								
								return( 0 );
							}
						}
					}		
				});
		}
		
		for (int i=0;i<subs.length;i++){
			
			Subscription sub = subs[i];
						
			SubscriptionHistory history = sub.getHistory();
			
			if ( !history.isEnabled()){
				
				continue;
			}
			
			synchronized( this ){
				
				Long	scan_due = (Long)sub.getUserData( SCHEDULER_NEXT_SCAN_KEY );
				
				if ( scan_due == null ){
					
					continue;
				}
				
				long diff = now - scan_due.longValue();
				
				if ( diff < -10*1000 ){
				
					continue;
				}
				
				sub.setUserData( SCHEDULER_NEXT_SCAN_KEY, null );
			}
			
			long	last_scan = history.getLastScanTime();

			boolean	download_attempted = true;
			
			try{	
				download_attempted = download( sub, true );
				
			}catch( Throwable e ){
								
			}finally{
				
				if ( download_attempted ){
					
					long	new_last_scan = history.getLastScanTime();
	
					if ( new_last_scan == last_scan ){
						
						scanFailed( sub );
						
					}else{
						
						scanSuccess( sub );
					}
				}
			}
		}
	}
	
	protected long
	getNextScan(
		Subscription		sub )
	{
		SubscriptionHistory	history = sub.getHistory();
				
		Long fail_count = (Long)sub.getUserData( SCHEDULER_FAILED_SCAN_CONSEC_KEY );
		
		if ( fail_count != null ){
			
			long 	fail_time = ((Long)sub.getUserData( SCHEDULER_FAILED_SCAN_TIME_KEY )).longValue();
			
			long	fails = fail_count.longValue();
			
			long	backoff = FAIL_INIT_DELAY;
			
			for (int i=1;i<fails;i++){
				
				backoff <<= 1;
				
				if ( backoff > FAIL_MAX_DELAY ){
					
					backoff = FAIL_MAX_DELAY;
					
					break;
				}
			}
			
			return( fail_time + backoff );
		}

		return( history.getNextScanTime() );
	}
	
	protected void
	scanSuccess(
		Subscription		sub )
	{
		sub.setUserData( SCHEDULER_FAILED_SCAN_CONSEC_KEY, null );
	}
	
	protected void
	scanFailed(
		Subscription		sub )
	{
		sub.setUserData( SCHEDULER_FAILED_SCAN_TIME_KEY, new Long( SystemTime.getCurrentTime()));
		
		Long fail_count = (Long)sub.getUserData( SCHEDULER_FAILED_SCAN_CONSEC_KEY );
		
		if ( fail_count == null ){
			
			fail_count = new Long(1);
			
		}else{
			
			fail_count = new Long(fail_count.longValue()+1);
		}
		
		sub.setUserData( SCHEDULER_FAILED_SCAN_CONSEC_KEY, fail_count );
	}
	
	protected void
	log(
		String		str )
	{
		manager.log( "Scheduler: " + str );
	}
	
	protected void
	log(
		String		str,
		Throwable 	e )
	{
		manager.log( "Scheduler: " + str, e );
	}
	
	public void
	subscriptionAdded(
		Subscription		subscription )
	{
		calculateSchedule();
	}
	
	public void
	subscriptionChanged(
		Subscription		subscription )
	{
		calculateSchedule();
	}
	
	public void 
	subscriptionSelected(
		Subscription subscription )
	{		
	}
	
	public void
	subscriptionRemoved(
		Subscription		subscription )
	{
		calculateSchedule();
	}
	
	public void
	associationsChanged(
		byte[]				association_hash )
	{
	}
	
	public void
	subscriptionRequested(
		URL					url )
	{	
	}
	
	public void 
	subscriptionRequested(
		SearchProvider sp,
		Map<String, Object> properties)
		
		throws SubscriptionException
	{
	}
}
