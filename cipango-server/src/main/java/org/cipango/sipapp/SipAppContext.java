// ========================================================================
// Copyright 2008-2009 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cipango.sipapp;

import static java.lang.Math.round;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionAttributeListener;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipErrorListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionAttributeListener;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.cipango.http.servlet.ConvergedSessionManager;
import org.cipango.log.event.Events;
import org.cipango.server.ID;
import org.cipango.server.Server;
import org.cipango.server.SipConnector;
import org.cipango.server.SipHandler;
import org.cipango.server.SipRequest;
import org.cipango.server.session.AppSession;
import org.cipango.server.session.AppSessionIf;
import org.cipango.server.session.CallSession;
import org.cipango.server.session.Session;
import org.cipango.server.session.SessionManager.SessionScope;
import org.cipango.server.session.scope.ScopedAppSession;
import org.cipango.server.session.scope.ScopedTimer;
import org.cipango.servlet.SipDispatcher;
import org.cipango.servlet.SipServletHandler;
import org.cipango.servlet.SipServletHolder;
import org.cipango.sip.NameAddr;
import org.cipango.sip.ParameterableImpl;
import org.cipango.sip.SipMethods;
import org.cipango.sip.SipParams;
import org.cipango.sip.SipURIImpl;
import org.cipango.sip.URIFactory;
import org.cipango.sip.security.AuthInfoImpl;
import org.cipango.sip.security.ConstraintSecurityHandler;
import org.cipango.util.ReadOnlySipURI;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.eclipse.jetty.webapp.WebAppContext;

public class SipAppContext extends WebAppContext implements SipHandler
{
	private static final Logger LOG = Log.getLogger(SipAppContext.class);
	
	public static final int VERSION_10 = 10;
	public static final int VERSION_11 = 11;
	
	private static final String SIP_CONFIGURATION_CLASS =
    	"org.cipango.sipapp.SipXmlConfiguration";
    
    public final static String SIP_DEFAULTS_XML="org/cipango/sipapp/sipdefault.xml";
        
    public final static String[] EXTENSIONS = { "MESSAGE", "INFO", "SUBSCRIBE", "NOTIFY", "UPDATE", "PUBLISH", "REFER",  "100rel" };
    
	public static final String[] SUPPORTED_RFC = new String[] {
		"2976", // The SIP INFO Method
		"3261", // SIP: Session Initiation Protocol
		"3262", // Reliability of Provisional Responses
		"3265", // (SIP)-Specific Event Notification. 
		"3311", // (SIP) UPDATE Method
		"3327", // SIP) Extension Header Field for Registering Non-Adjacent Contacts (Path header)
		"3428", // SIP Extension for Instant Messaging  
		"3515", // SIP Refer Method
		"3903", // SIP Extension for Event State Publication
		"5658", // Addressing Record-Route Issues in SIP
		"6026"	// Correct Transaction Handling for 2xx Responses to Session Initiation Protocol (SIP) INVITE Requests
	};
	
	public static final String EXTERNAL_INTERFACES = "org.cipango.externalOutboundInterfaces";
    
    /*
    public final CLFireEvent<SipErrorListener, SipErrorEvent> _noAck = new CLFireEvent<SipErrorListener, SipErrorEvent>()
    {
    	public SipErrorListener[] getEventListeners() { return _errorListeners; }
    	public void fireEvent(SipErrorListener listener, SipErrorEvent event) { listener.noAckReceived(event); }
    };
    
    public final CLFireEvent<SipErrorListener, SipErrorEvent> _noPrack = new CLFireEvent<SipErrorListener, SipErrorEvent>()
    {
    	public SipErrorListener[] getEventListeners() { return _errorListeners; }
    	public void fireEvent(SipErrorListener listener, SipErrorEvent event) { listener.noPrackReceived(event); }
    };
    */

    private String _name;
    
    private TimerListener[] _timerListeners = new TimerListener[0];
    private SipApplicationSessionListener[] _appSessionListeners = new SipApplicationSessionListener[0];
    private SipErrorListener[] _errorListeners = new SipErrorListener[0];
    private SipApplicationSessionAttributeListener[] _appSessionAttributeListeners = new SipApplicationSessionAttributeListener[0];
    private SipSessionListener[] _sessionListeners = new SipSessionListener[0];
    private SipSessionAttributeListener[] _sessionAttributeListeners = new SipSessionAttributeListener[0];
    private SipServletListener[] _servletListeners = new SipServletListener[0];

    private int _sessionTimeout = -1;
    private int _proxyTimeout = -1;
    
    private final CounterStatistic _sessionsStats = new CounterStatistic();
    private final SampleStatistic _sessionTimeStats = new SampleStatistic();
       
    private String _defaultsSipDescriptor=SIP_DEFAULTS_XML;
    private final List<String> _overrideSipDescriptors = new ArrayList<String>();
    
    private SipFactory _sipFactory = new Factory();
    private TimerService _timerService = new Timer();
    private SipSessionsUtil _sipSessionsUtil = new SessionUtil();
    private Method _sipApplicationKeyMethod;
    
    private int _specVersion;
    
    private SipMetaData _sipMetaData = new SipMetaData();
    
    private ConstraintSecurityHandler _sipSecurityHandler;
    
	public SipAppContext() 
	{
		super();
		setSessionHandler(new SessionHandler(new ConvergedSessionManager()));
		setServletHandler(new SipServletHandler());
		setConfigurationClasses((String[]) LazyList.addToArray(
				getConfigurationClasses(),
				SIP_CONFIGURATION_CLASS,
				String.class));
        _scontext = new Context();
        setSystemClasses((String[]) LazyList.addToArray(getSystemClasses(), "org.cipango.", String.class));
	}
	
	
	public SipAppContext(String sipApp, String contextPath) 
	{
		this();
		setWar(sipApp);
		setContextPath(contextPath);
	}
	
    public SipAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler) 
    {
    	super(sessionHandler, securityHandler, servletHandler, errorHandler);
    	_scontext = new Context();
    	setSystemClasses((String[]) LazyList.addToArray(getSystemClasses(), "org.cipango.", String.class));
    	// FIXME do more???
    }

	public void addSipServlet(SipServletHolder servlet)
	{
		((SipServletHandler) _servletHandler).addSipServlet(servlet);
	}
	
	public void handle(SipServletMessage message) throws ServletException, IOException 
	{
		// TODO if (getUnavailableException() != null) send 503 ???
		
		ClassLoader oldClassLoader = null;
		Thread currentThread = null;
		
		if (getClassLoader() != null)
		{
			currentThread = Thread.currentThread();
			oldClassLoader = currentThread.getContextClassLoader();
			currentThread.setContextClassLoader(getClassLoader());
		}
		try
		{
			if (_sipSecurityHandler != null)
				_sipSecurityHandler.handle(message);
			else
				getSipServletHandler().handle(message);
		}
		finally
		{
			if (getClassLoader() != null)
			{
				currentThread.setContextClassLoader(oldClassLoader);
			}
		}
	}
	
	public void serverStarted()
	{	
		ClassLoader oldClassLoader = null;
		Thread currentThread = null;
		
		if (getClassLoader() != null)
		{
			currentThread = Thread.currentThread();
			oldClassLoader = currentThread.getContextClassLoader();
			currentThread.setContextClassLoader(getClassLoader());
		}
		try
		{
			List<SipURI> outbounds = new ArrayList<SipURI>();
			List<SipURI> externals = new ArrayList<SipURI>();

			SipConnector[] connectors = getServer().getConnectorManager().getConnectors();
			
			if (connectors != null)
			{
				for (SipConnector connector : connectors) 
				{
					SipURI uri = new SipURIImpl(null, connector.getAddr().getHostAddress(), connector.getLocalPort());
					if (!outbounds.contains(uri))
						outbounds.add(new ReadOnlySipURI(uri));
					if (!externals.contains(connector.getSipUri()))
						externals.add(new ReadOnlySipURI(connector.getSipUri()));
				}
			}
			setAttribute(SipServlet.OUTBOUND_INTERFACES, Collections.unmodifiableList(outbounds));
			setAttribute(EXTERNAL_INTERFACES, Collections.unmodifiableList(externals));
			
			SipServletHolder[] holders = getSipServletHandler().getSipServlets();
			if (holders != null)
			{
				for (SipServletHolder holder : holders)
				{
					if (holder.getServletInstance() != null && holder.getServletInstance() instanceof SipServlet)
					{
						fireServletInitialized((SipServlet) holder.getServletInstance());
					}
				}
			}
		}
		finally
		{
			if (getClassLoader() != null)
			{
				currentThread.setContextClassLoader(oldClassLoader);
			}
		}
	}
	
	public void fireServletInitialized(SipServlet servlet)
	{
		for (int i = 0; i < _servletListeners.length; i++)
		{
			try
			{
				_servletListeners[i].servletInitialized(new SipServletContextEvent(servlet.getServletContext(), servlet));
			}
			catch (Throwable t)
			{
				LOG.debug(t);
			}
		}
	}
    
    public SipApplicationSessionListener[] getSipApplicationSessionListeners()
    {
        return _appSessionListeners;
    }
    
    public TimerListener[] getTimerListeners()
    {
        return _timerListeners;
    }
    
    public SipErrorListener[] getSipErrorListeners()
    {
        return _errorListeners;
    }
    
    public SipApplicationSessionAttributeListener[] getSipApplicationSessionAttributeListeners()
    {
    	return _appSessionAttributeListeners;
    }
    
    public SipSessionListener[] getSipSessionListeners()
    {
    	return _sessionListeners;
    }
    
    public SipSessionAttributeListener[] getSessionAttributeListeners()
    {
    	return _sessionAttributeListeners;
    }
    
    public void setProxyTimeout(int timeout)
    {
        _proxyTimeout = timeout;
    }
    
    public int getProxyTimeout() 
    {
        return _proxyTimeout;
    }
    
    @Override
    public void setEventListeners(EventListener[] eventListeners)
    {
        super.setEventListeners(eventListeners);
        
        Object timerListeners = null;
        Object appSessionListeners = null;
        Object errorListeners = null;
        Object appSessionAttributeListeners = null;
        Object sessionListeners = null;
        Object sessionAttributesListeners = null;
        Object servletListeners = null;
        
        for (int i = 0; eventListeners != null && i < eventListeners.length; i++)
        {
            EventListener listener = eventListeners[i];
            if (listener instanceof TimerListener)
                timerListeners = LazyList.add(timerListeners, listener);
            if (listener instanceof SipApplicationSessionListener)
                appSessionListeners = LazyList.add(appSessionListeners, listener);
            if (listener instanceof SipErrorListener)
                errorListeners = LazyList.add(errorListeners, listener);
            if (listener instanceof SipApplicationSessionAttributeListener)
            	appSessionAttributeListeners = LazyList.add(appSessionAttributeListeners, listener);
            if (listener instanceof SipSessionListener)
            	sessionListeners = LazyList.add(sessionListeners, listener);
            if (listener instanceof SipSessionAttributeListener)
            	sessionAttributesListeners = LazyList.add(sessionAttributesListeners, listener);
            if (listener instanceof SipServletListener)
            	servletListeners = LazyList.add(servletListeners, listener);
        }
        _timerListeners = (TimerListener[]) 
            LazyList.toArray(timerListeners, TimerListener.class);
        _appSessionListeners = (SipApplicationSessionListener[]) 
            LazyList.toArray(appSessionListeners, SipApplicationSessionListener.class);
        _errorListeners = (SipErrorListener[])
            LazyList.toArray(errorListeners, SipErrorListener.class);
        _appSessionAttributeListeners = (SipApplicationSessionAttributeListener[])
        	LazyList.toArray(appSessionAttributeListeners, SipApplicationSessionAttributeListener.class);
        _sessionListeners = (SipSessionListener[])
        	LazyList.toArray(sessionListeners, SipSessionListener.class);
        _sessionAttributeListeners = (SipSessionAttributeListener[])
        	LazyList.toArray(sessionAttributesListeners, SipSessionAttributeListener.class);
        _servletListeners = (SipServletListener[])
        	LazyList.toArray(servletListeners, SipServletListener.class);
    }
    
    public void fire(EventListener[] listeners, Method method, Object... args)
    {
		ClassLoader oldClassLoader = null;
		Thread currentThread = null;
		
		if (getClassLoader() != null)
		{
			currentThread = Thread.currentThread();
			oldClassLoader = currentThread.getContextClassLoader();
			currentThread.setContextClassLoader(getClassLoader());
		}

		for (int i = 0; i < listeners.length; i++)
		{
			try
			{
				method.invoke(listeners[i], args);
			}
			catch (Throwable t)
			{
				LOG.debug(t);
			}
		}
		if (getClassLoader() != null)
		{
			currentThread.setContextClassLoader(oldClassLoader);
		}
    }
    
    @SuppressWarnings("deprecation")
	@Override
	protected void startContext() throws Exception 
	{
	    setAttribute(SipServlet.PRACK_SUPPORTED, Boolean.TRUE);
		setAttribute(SipServlet.SIP_FACTORY, getSipFactory());
		setAttribute(SipServlet.TIMER_SERVICE, getTimerService());
		setAttribute(SipServlet.SIP_SESSIONS_UTIL, getSipSessionsUtil());
		setAttribute(SipServlet.SUPPORTED, Collections.unmodifiableList(Arrays.asList(EXTENSIONS)));
		setAttribute(SipServlet.SUPPORTED_RFCs, Collections.unmodifiableList(Arrays.asList(SUPPORTED_RFC)));
		
		
		super.startContext();
		
        SipHandler handler = getSipServletHandler();
        if (_sipSecurityHandler!=null)
        {
        	_sipSecurityHandler.setHandler(handler);
            handler=_sipSecurityHandler;
            
            _sipSecurityHandler.start(); // FIXME when should it be started
        }
		      		
		if (_servletHandler != null && _servletHandler.isStarted())
    	{
    	    for (Decorator decorator : getDecorators())
    	    {
    	        if(getSipServletHandler().getSipServlets()!=null)
    	            for (SipServletHolder holder:getSipServletHandler().getSipServlets())
    	                decorator.decorateServletHolder(holder);
    	    } 

            ((SipServletHandler) _servletHandler).initializeSip();
    	}
    }
    
    @Override
    public void configure() throws Exception
    {
    	// We must execute code in this order: configure() resolve Metadata and super.super.startContext()
    	super.configure();
		//resolve the metadata
        _sipMetaData.resolve(this);
    }
    
    public String getDefaultName()
    {
    	String name = getContextPath();
		if (name != null && name.startsWith("/"))
			name = name.substring(1);
		return name;
    }
    
    @Override
    protected void doStart() throws Exception
    {
    	if (getSipSecurityHandler() != null)
    		_sipSecurityHandler.setServer(getServer());
    	
    	super.doStart();
    	
    	if (!isAvailable())
    	{
    		if (_name == null)
				_name = getDefaultName();
			Events.fire(Events.DEPLOY_FAIL, 
        			"Unable to deploy application " + getName()
        			+ ": " + getUnavailableException().getMessage());
    	}
    	else if (hasSipServlets())
    	{
    		getServer().applicationStarted(this);
    	}
    }
    
	@Override
	protected void doStop() throws Exception
	{
		if (hasSipServlets() && isAvailable())
			getServer().applicationStopped(this);
		
		if (_sipMetaData != null)
			_sipMetaData.clear();
		_sipMetaData =new SipMetaData();
		
		if (_sipSecurityHandler != null)
			_sipSecurityHandler.stop();
		
		super.doStop();
	}
	
    public SipServletHandler getSipServletHandler()
    {
        return (SipServletHandler) getServletHandler();
    }
    
    public boolean hasSipServlets()
    {
    	SipServletHolder[] holders = getSipServletHandler().getSipServlets();
    	return holders != null && holders.length != 0;
    }
    
	public void incrementSessions()
	{
		_sessionsStats.increment();
	}
	
	public void decrementSessions(long sessionLifetime)
	{
		_sessionsStats.decrement();
		_sessionTimeStats.set(round((sessionLifetime)/1000.0));
	}
	
	public long getSessions()
	{
		return _sessionsStats.getCurrent();
	}
	
	public long getSessionsTotal()
	{
		return _sessionsStats.getTotal();
	}
	
	public long getSessionsMax()
	{
		return _sessionsStats.getMax();
	}
	
    /**
     * @return maximum amount of time session remained valid
     */
    public long getSessionTimeMax()
    {
        return _sessionTimeStats.getMax();
    }
    
    /**
     * Reset statistics values
     */
    public void statsReset()
    {
    	_sessionsStats.reset(getSessions());
    	_sessionTimeStats.reset();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return total amount of time all sessions remained valid
     */
    public long getSessionTimeTotal()
    {
        return _sessionTimeStats.getTotal();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return mean amount of time session remained valid
     */
    public double getSessionTimeMean()
    {
        return _sessionTimeStats.getMean();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return standard deviation of amount of time session remained valid
     */
    public double getSessionTimeStdDev()
    {
        return _sessionTimeStats.getStdDev();
    }
	
	public void setDefaultsSipDescriptor(String defaultsDescriptor)
	{
		_defaultsSipDescriptor = defaultsDescriptor;
	}
	
	public String getDefaultsSipDescriptor()
	{
		return _defaultsSipDescriptor;
	}
	 
    /**
     * The override descriptor is a sip.xml format file that is applied to the context after the standard WEB-INF/sip.xml
     * @param overrideDescriptor The overrideDescritpor to set.
     * @deprecated use {@link #setOverrideDescriptors(List)}
     */
    public void setOverrideSipDescriptor(String overrideDescriptor)
    {
        _overrideSipDescriptors.clear();
        _overrideSipDescriptors.add(overrideDescriptor);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a sip.xml format file that is applied to the context after the standard WEB-INF/sip.xml
     * @param overrideDescriptor The overrideDescriptors (file or URL) to set.
     */
    public void setOverrideSipDescriptors(List<String> overrideDescriptors)
    {
    	_overrideSipDescriptors.clear();
    	_overrideSipDescriptors.addAll(overrideDescriptors);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a sip.xml format file that is applied to the context after the standard WEB-INF/sip.xml
     * @param overrideDescriptor The overrideDescriptor (file or URL) to add.
     */
    public void addOverrideSipDescriptor(String overrideDescriptor)
    {
    	_overrideSipDescriptors.add(overrideDescriptor);
    }
    
    /**
     * The override descriptor is a sip.xml format file that is applied to the context after the standard WEB-INF/sip.xml
     * @return Returns the Override Descriptor.
     * @deprecated use {@link #getOverrideDescriptors()}
     */
    public String getOverrideSipDescriptor()
    {
        if (_overrideSipDescriptors.size()!=1)
            return null;
        return _overrideSipDescriptors.get(0);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a sip.xml format file that is applied to the context after the standard WEB-INF/sip.xml
     * @return Returns the Override Descriptor list
     */
    public List<String> getOverrideSipDescriptors()
    {
        return Collections.unmodifiableList(_overrideSipDescriptors);
    }
    
	public void setSessionTimeout(int minutes) 
    {
		if (minutes <= 0)
			_sessionTimeout = -1;
		else 
			_sessionTimeout = minutes;
	}
	
	public int getSessionTimeout() 
    {
		return _sessionTimeout;
	}
	
	public void setName(String name)
	{
		_name = name; 
	}
    
    public String getName() 
    {
    	return _name;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isServerClass(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);

        String[] server_classes = getServerClasses();
        if (server_classes!=null)
        {
            for (int i=0;i<server_classes.length;i++)
            {
                boolean result=true;
                String c=server_classes[i];
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isSystemClass(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);
        String[] system_classes = getSystemClasses();
        if (system_classes!=null)
        {
            for (int i=0;i<system_classes.length;i++)
            {
                boolean result=true;
                String c=system_classes[i];
                
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        
        return false;
        
    }
    
	public SipFactory getSipFactory()
	{
		return _sipFactory;
	}


	public TimerService getTimerService()
	{
		return _timerService;
	}

	public SipSessionsUtil getSipSessionsUtil()
	{
		return _sipSessionsUtil;
	}
	
	public String getSipApplicationKey(SipServletRequest request)
	{
		if (_sipApplicationKeyMethod == null)
			return null;
		try
		{
			return (String) _sipApplicationKeyMethod.invoke(null, request);
		}
		catch (Throwable e)
		{
			LOG.debug("Fail to get SipApplicationKey", e);
			return null;
		}
	}
	
	public Method getSipApplicationKeyMethod()
	{
		return _sipApplicationKeyMethod;
	}
	
	public void setSipApplicationKeyMethod(Method sipApplicationKeyMethod)
	{
		_sipApplicationKeyMethod = sipApplicationKeyMethod;
	}
	
    public int getSpecVersion()
	{
		return _specVersion;
	}
    
    public String getSpecVersionAsString()
	{
		switch (_specVersion)
		{
		case VERSION_10:
			return "1.0";
		case VERSION_11:
			return "1.1";
		default:
			return String.valueOf(_specVersion);
		}
	}

	public void setSpecVersion(int specVersion)
	{
		_specVersion = specVersion;
	}
	
	@Override
	public Server getServer()
	{
		return (Server) super.getServer();
	}
	
	public SipMetaData getSipMetaData()
	{
		return _sipMetaData;
	}
	
	public ConstraintSecurityHandler getSipSecurityHandler()
	{
		if (_sipSecurityHandler == null && (_options & SECURITY) != 0 && !isStarted())
			setSipSecurityHandler(newSipSecurityHandler());
		return _sipSecurityHandler;
	}
	
	protected ConstraintSecurityHandler newSipSecurityHandler()
    {
        return new ConstraintSecurityHandler();
    }


	public void setSipSecurityHandler(ConstraintSecurityHandler sipSecurityHandler)
	{
		if (getServer() != null)
			getServer().getContainer().update(this, _sipSecurityHandler, sipSecurityHandler, "sipSecurityHandler");

		_sipSecurityHandler = sipSecurityHandler;
	}
	
    public boolean isUnavailable()
    {
    	return getUnavailableException() != null;
    }
		
	@Override
	public String toString()
	{
		if (_name != null)
			return super.toString() + ", " + _name;
		return super.toString();
	}

	    	
    public class Timer implements TimerService
    {
        public ServletTimer createTimer(SipApplicationSession session, long delay, boolean isPersistent, Serializable info) 
        {
            return new ScopedTimer(((AppSessionIf) session).getAppSession(), delay, isPersistent, info);
        }

        public ServletTimer createTimer(SipApplicationSession session, long delay, long period, boolean fixedDelay, boolean isPersistent, Serializable info) 
        {
        	return new ScopedTimer(((AppSessionIf) session).getAppSession(), delay, period, fixedDelay, isPersistent, info);
        }
    }
    
    public class Factory implements SipFactory
    {
        private Factory() { }
       
        public URI createURI(String uri) throws ServletParseException 
        {
            return URIFactory.parseURI(uri);
        }

        public SipURI createSipURI(String user, String host) 
        {
            return new SipURIImpl(user, host, -1);
        }

        public Address createAddress(String address) throws ServletParseException 
        {
            return new NameAddr(address);
        }

        public Address createAddress(URI uri) 
        {
            return new NameAddr(uri);
        }

        public Address createAddress(URI uri, String displayName) 
        {
            return new NameAddr(uri, displayName);
        }

        public SipServletRequest createRequest(SipApplicationSession sipAppSession,
                String method, Address from, Address to) 
        {
            if (SipMethods.ACK.equalsIgnoreCase(method) || SipMethods.CANCEL.equalsIgnoreCase(method)) 
                throw new IllegalArgumentException("Method cannot be ACK nor CANCEL");
            
            NameAddr local = (NameAddr) from.clone();
            NameAddr remote = (NameAddr) to.clone();
            
            local.setParameter(SipParams.TAG, ID.newTag());
            remote.removeParameter(SipParams.TAG);
            
            AppSession appSession = ((AppSessionIf) sipAppSession).getAppSession();           
            
            String cid = ID.newCallId(appSession.getCallSession().getId());
            
            Session session = appSession.createUacSession(cid, local, remote); 
            session.setHandler(getSipServletHandler().getDefaultServlet());
            
            SipRequest request = (SipRequest) session.createRequest(method);
            request.setInitial(true);
            request.setRoutingDirective(SipApplicationRoutingDirective.NEW, null);
           
            return request;
        }

        public SipServletRequest createRequest(SipApplicationSession appSession, 
                String method, URI from, URI to) 
        {
            return createRequest(appSession, method, createAddress(from), createAddress(to));
        }

        public SipServletRequest createRequest(SipApplicationSession appSession,
                String method, String from, String to) throws ServletParseException 
        {
            return createRequest(appSession, method, createAddress(from), createAddress(to));
        }
        
        public SipServletRequest createRequest(SipServletRequest srcRequest, boolean sameCallId) 
        {
        	SipRequest origRequest = (SipRequest) srcRequest;
        	
        	NameAddr local = (NameAddr) origRequest.from().clone();
        	local.setParameter(SipParams.TAG, ID.newTag());
        	
        	NameAddr remote = (NameAddr) origRequest.to().clone();
        	remote.removeParameter(SipParams.TAG);
        	
        	String callId = null;
        	
        	if (sameCallId)
        		callId = origRequest.getCallId();
        	else
        		callId = ID.newCallId(origRequest.getCallId());
        	
            AppSession appSession = ((SipRequest) srcRequest).appSession(); 
            
            Session session = appSession.createUacSession(callId, local, remote);
            session.setHandler(getSipServletHandler().getDefaultServlet());

            SipRequest request = session.getUA().createRequest((SipRequest) srcRequest);
            request.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, srcRequest);
            request.setInitial(true);
            
            return request;
        }
        
        public SipApplicationSession createApplicationSession()
        {
        	Server server = getServer();
        	
        	SessionScope scope = server.getSessionManager().openScope(ID.newCallId());
	        try
	        {
	        	AppSession session = scope.getCallSession().createAppSession(SipAppContext.this, ID.newAppSessionId());
	        	return new ScopedAppSession(session);
	        }
	        finally
	        {
	        	scope.close();
	        }
        }

		public SipApplicationSession createApplicationSessionByKey(String key)
		{
			return getSipSessionsUtil().getApplicationSessionByKey(key, true);
		}

		public AuthInfo createAuthInfo()
		{
			return new AuthInfoImpl();
		}

		public Parameterable createParameterable(String s) throws ServletParseException
		{
			return new ParameterableImpl(s);
		} 
    }

    class SessionUtil implements SipSessionsUtil
    {
		public SipApplicationSession getApplicationSessionById(String applicationSessionId)
		{
			if (applicationSessionId == null)
				throw new NullPointerException("applicationSessionId is null");
			
			int i = applicationSessionId.indexOf(';'); // TODO id helper class
			if (i < 0) 
				return null;
			
			String id = applicationSessionId.substring(0, i);
			
			CallSession callSession = getServer().getSessionManager().get(id);
			if (callSession == null)
				return null;
			
			AppSession appSession = callSession.getAppSession(applicationSessionId.substring(i+1));
			if (appSession == null)
				return null;
			else
				return new ScopedAppSession(appSession);
		}

		public SipApplicationSession getApplicationSessionByKey(String key, boolean create)
		{
			if (key == null)
				throw new NullPointerException("key is null");
			
			String id = ID.getIdFromKey(getName(), key);

			SessionScope tx = getServer().getSessionManager().openScope(id);
			try
			{
				AppSession appSession = tx.getCallSession().getAppSession(id);
				if (appSession == null)
				{
					if (create)
						appSession = tx.getCallSession().createAppSession(SipAppContext.this, id);
					else 
						return null;
				}
				return new ScopedAppSession(appSession);
			}
			finally
			{
				tx.close();
			}			
		}

		public SipSession getCorrespondingSipSession(SipSession session, String headerName)
		{
			return null;
		}
    }
    
    public class Context extends WebAppContext.Context 
    {
    	@Override
		public RequestDispatcher getNamedDispatcher(String name)
        {
            if (_servletHandler != null)
            {
                SipServletHolder holder = ((SipServletHandler) _servletHandler).getHolder(name);
                if (holder != null)
                	return new SipDispatcher(SipAppContext.this, holder);
            }
            return super.getNamedDispatcher(name);
        }
        
    	@Override
        public String getServerInfo()
        {
            return "cipango-2.0";
        }
        
    }
    
    abstract class CLFireEvent<L extends EventListener, E>
    {
    	public abstract void fireEvent(L listener, E event);
    	public abstract L[] getEventListeners();
    	
    	public boolean hasListeners()
    	{
    		L listeners[] = getEventListeners();
    		return listeners != null && listeners.length > 0;
    	}
    	
    	public void fire(E event) 
    	{
    		L listeners[] = getEventListeners();
    		
    		if (listeners != null && listeners.length > 0)
    		{
	    		ClassLoader oldClassLoader = null;
	    		Thread currentThread = null;
	    		
	    		if (getClassLoader() != null)
	    		{
	    			currentThread = Thread.currentThread();
	    			oldClassLoader = currentThread.getContextClassLoader();
	    			currentThread.setContextClassLoader(getClassLoader());
	    		}
	    		try
	    		{
	    			for (int i = 0; i < listeners.length; i++)
	    			{
	    				fireEvent(listeners[i], event);
	    			}
	    		}
	    		finally
	    		{
	    			if (getClassLoader() != null)
	    			{
	    				currentThread.setContextClassLoader(oldClassLoader);
	    			}
	    		}
	    	}
    	}
    }

	
    
}
