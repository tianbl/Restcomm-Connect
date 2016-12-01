/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.restcomm.connect.interpreter;

import java.io.IOException;
// import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
// import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration.Configuration;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.message.BasicNameValuePair;
import org.joda.time.DateTime;
import org.restcomm.connect.dao.DaoManager;
import org.restcomm.connect.dao.NotificationsDao;
import org.restcomm.connect.dao.GeolocationDao;
import org.restcomm.connect.email.EmailService;
import org.restcomm.connect.email.api.EmailRequest;
import org.restcomm.connect.email.api.EmailResponse;
import org.restcomm.connect.email.api.Mail;
import org.restcomm.connect.dao.entities.Notification;
import org.restcomm.connect.commons.dao.Sid;
import org.restcomm.connect.dao.entities.Geolocation;
import org.restcomm.connect.commons.fsm.Action;
import org.restcomm.connect.commons.fsm.FiniteStateMachine;
import org.restcomm.connect.commons.fsm.State;
import org.restcomm.connect.commons.fsm.Transition;
import org.restcomm.connect.http.client.Downloader;
import org.restcomm.connect.http.client.DownloaderResponse;
import org.restcomm.connect.http.client.HttpRequestDescriptor;
import org.restcomm.connect.http.client.HttpResponseDescriptor;
import org.restcomm.connect.interpreter.rcml.Attribute;
import org.restcomm.connect.interpreter.rcml.GetNextVerb;
import org.restcomm.connect.interpreter.rcml.Parser;
import org.restcomm.connect.interpreter.rcml.ParserFailed;
import org.restcomm.connect.interpreter.rcml.Tag;
import org.restcomm.connect.commons.patterns.Observe;
import org.restcomm.connect.geolocation.api.CreateGeolocationSession;
import org.restcomm.connect.geolocation.api.DestroyGeolocationSession;
import org.restcomm.connect.geolocation.api.GetLastGeolocationRequest;
import org.restcomm.connect.geolocation.api.GeolocationServiceResponse;
import org.restcomm.connect.geolocation.api.GeolocationSessionAttribute;
import org.restcomm.connect.geolocation.api.GeolocationSessionInfo;
import org.restcomm.connect.geolocation.api.GeolocationSessionRequest;
import org.restcomm.connect.geolocation.api.GeolocationSessionResponse;

import akka.actor.ActorRef;
import akka.actor.Actor;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorContext;
import akka.actor.UntypedActorFactory;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.restcomm.connect.interpreter.rcml.Verbs;

import static org.restcomm.connect.interpreter.rcml.Verbs.record;

/**
 * @author quintana.thomas@gmail.com (Thomas Quintana)
 * @author <a href="mailto:fernando.mendioroz@telestax.com"> Fernando Mendioroz </a>
 */
public final class GeolocationInterpreter extends UntypedActor {

    private static final int ERROR_NOTIFICATION = 0;
    private static final int WARNING_NOTIFICATION = 1;
    static String EMAIL_SENDER;
    // Logger
    private final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);
    // States for the FSM.
    private final State uninitialized;
    private final State acquiringLastGeolocationRequest;
    private final State downloadingRcml;
    private final State downloadingFallbackRcml;
    private final State ready;
    private final State redirecting;
    private final State creatingGeolocationSession;
    private final State sendingEmail;
    private final State sendingGeolocation;
    private final State waitingForGeolocationResponses;
    private final State finished;
    // FSM.
    private final FiniteStateMachine fsm;
    // Geolocation Stuff.
    private final ActorRef service;
    private final Map<Sid, ActorRef> sessions;
    private Sid initialSessionSid;
    private ActorRef initialSession;
    private ActorRef mailerService;
    private GeolocationSessionRequest initialSessionRequest;
    // HTTP Stuff.
    private final ActorRef downloader;
    // The storage engine.
    private final DaoManager storage;
    //Runtime configuration
    private final Configuration runtime;
    // User specific configuration.
    private final Configuration configuration;
    // Information to reach the application that will be executed
    // by this interpreter.
    private final Sid accountId;
    private final String version;
    private final URI url;
    private final String method;
    private final URI fallbackUrl;
    private final String fallbackMethod;
    // application data.
    private HttpRequestDescriptor request;
    private HttpResponseDescriptor response;
    // The RCML parser.
    private ActorRef parser;
    private Tag verb;
    private boolean normalizeNumber;
    private ConcurrentHashMap<String, String> customHttpHeaderMap = new ConcurrentHashMap<String, String>();
    private ConcurrentHashMap<String, String> customRequestHeaderMap;

    public GeolocationInterpreter(final ActorRef service, final Configuration configuration, final DaoManager storage,
                                  final Sid accountId, final String version, final URI url, final String method, final URI fallbackUrl,
                                  final String fallbackMethod) {
        super();
        final ActorRef source = self();
        uninitialized = new State("uninitialized", null, null);
        acquiringLastGeolocationRequest = new State("acquiring last geolocation service event", new AcquiringLastGeolocationEvent(source), null);
        downloadingRcml = new State("downloading rcml", new DownloadingRcml(source), null);
        downloadingFallbackRcml = new State("downloading fallback rcml", new DownloadingFallbackRcml(source), null);
        ready = new State("ready", new Ready(source), null);
        redirecting = new State("redirecting", new Redirecting(source), null);
        creatingGeolocationSession = new State("creating geolocation session", new CreatingGeolocationSession(source), null);
        sendingGeolocation = new State("sending geolocation request", new SendingGeolocation(source), null);
        waitingForGeolocationResponses = new State("waiting for geolocation responses", new WaitingForGeolocationResponses(source), null);
        sendingEmail = new State("sending Email", new SendingEmail(source), null);
        finished = new State("finished", new Finished(source), null);
        // Initialize the transitions for the FSM.
        final Set<Transition> transitions = new HashSet<Transition>();
        transitions.add(new Transition(uninitialized, acquiringLastGeolocationRequest));
        transitions.add(new Transition(acquiringLastGeolocationRequest, downloadingRcml));
        transitions.add(new Transition(acquiringLastGeolocationRequest, finished));
        transitions.add(new Transition(acquiringLastGeolocationRequest, sendingEmail));
        transitions.add(new Transition(downloadingRcml, ready));
        transitions.add(new Transition(downloadingRcml, downloadingFallbackRcml));
        transitions.add(new Transition(downloadingRcml, finished));
        transitions.add(new Transition(downloadingRcml, sendingEmail));
        transitions.add(new Transition(downloadingFallbackRcml, ready));
        transitions.add(new Transition(downloadingFallbackRcml, finished));
        transitions.add(new Transition(downloadingFallbackRcml, sendingEmail));
        transitions.add(new Transition(ready, redirecting));
        transitions.add(new Transition(ready, creatingGeolocationSession));
        transitions.add(new Transition(ready, waitingForGeolocationResponses));
        transitions.add(new Transition(ready, sendingEmail));
        transitions.add(new Transition(ready, finished));
        transitions.add(new Transition(redirecting, ready));
        transitions.add(new Transition(redirecting, creatingGeolocationSession));
        transitions.add(new Transition(redirecting, finished));
        transitions.add(new Transition(redirecting, sendingEmail));
        transitions.add(new Transition(redirecting, waitingForGeolocationResponses));
        transitions.add(new Transition(creatingGeolocationSession, sendingGeolocation));
        transitions.add(new Transition(creatingGeolocationSession, waitingForGeolocationResponses));
        transitions.add(new Transition(creatingGeolocationSession, sendingEmail));
        transitions.add(new Transition(creatingGeolocationSession, finished));
        transitions.add(new Transition(sendingGeolocation, ready));
        transitions.add(new Transition(sendingGeolocation, redirecting));
        transitions.add(new Transition(sendingGeolocation, creatingGeolocationSession));
        transitions.add(new Transition(sendingGeolocation, waitingForGeolocationResponses));
        transitions.add(new Transition(sendingGeolocation, sendingEmail));
        transitions.add(new Transition(sendingGeolocation, finished));
        transitions.add(new Transition(waitingForGeolocationResponses, waitingForGeolocationResponses));
        transitions.add(new Transition(waitingForGeolocationResponses, sendingEmail));
        transitions.add(new Transition(waitingForGeolocationResponses, finished));
        transitions.add(new Transition(sendingEmail, ready));
        transitions.add(new Transition(sendingEmail, redirecting));
        transitions.add(new Transition(sendingEmail, creatingGeolocationSession));
        transitions.add(new Transition(sendingEmail, waitingForGeolocationResponses));
        transitions.add(new Transition(sendingEmail, finished));
        // Initialize the FSM.
        this.fsm = new FiniteStateMachine(uninitialized, transitions);
        // Initialize the runtime stuff.
        this.service = service;
        this.downloader = downloader();
        this.storage = storage;
        this.runtime = configuration.subset("runtime-settings");
        this.configuration = configuration.subset("Geolocation-aggregator");
        this.accountId = accountId;
        this.version = version;
        this.url = url;
        this.method = method;
        this.fallbackUrl = fallbackUrl;
        this.fallbackMethod = fallbackMethod;
        this.sessions = new HashMap<Sid, ActorRef>();
        this.normalizeNumber = runtime.getBoolean("normalize-numbers-for-outbound-calls");
    }

    private ActorRef downloader() {
        final UntypedActorContext context = getContext();
        return context.actorOf(new Props(new UntypedActorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public UntypedActor create() throws Exception {
                return new Downloader();
            }
        }));
    }

    ActorRef mailer(final Configuration configuration) {
        final UntypedActorContext context = getContext();
        return context.actorOf(new Props(new UntypedActorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public Actor create() throws Exception {
                return new EmailService(configuration);
            }
        }));
    }

    protected String format(final String number) {
        if(normalizeNumber) {
            final PhoneNumberUtil numbersUtil = PhoneNumberUtil.getInstance();
            try {
                final PhoneNumber result = numbersUtil.parse(number, "US");
                return numbersUtil.format(result, PhoneNumberFormat.E164);
            } catch (final NumberParseException ignored) {
                return null;
            }
        } else {
            return number;
        }
    }

    protected void invalidVerb(final Tag verb) {
        final ActorRef self = self();
        final Notification notification = notification(WARNING_NOTIFICATION, 14110, "Invalid Verb for Geolocation Reply");
        final NotificationsDao notifications = storage.getNotificationsDao();
        notifications.addNotification(notification);
        // Get the next verb.
        final GetNextVerb next = GetNextVerb.instance();
        parser.tell(next, self);
    }

    protected Notification notification(final int log, final int error, final String message) {
        final Notification.Builder builder = Notification.builder();
        final Sid sid = Sid.generate(Sid.Type.NOTIFICATION);
        builder.setSid(sid);
        builder.setAccountSid(accountId);
        builder.setApiVersion(version);
        builder.setLog(log);
        builder.setErrorCode(error);
        final String base = runtime.getString("error-dictionary-uri");
        StringBuilder buffer = new StringBuilder();
        buffer.append(base);
        if (!base.endsWith("/")) {
            buffer.append("/");
        }
        buffer.append(error).append(".html");
        final URI info = URI.create(buffer.toString());
        builder.setMoreInfo(info);
        builder.setMessageText(message);
        final DateTime now = DateTime.now();
        builder.setMessageDate(now);
        if (request != null) {
            builder.setRequestUrl(request.getUri());
            builder.setRequestMethod(request.getMethod());
            builder.setRequestVariables(request.getParametersAsString());
        }
        if (response != null) {
            builder.setResponseHeaders(response.getHeadersAsString());
            final String type = response.getContentType();
            if (type != null && (type.contains("text/xml") || type.contains("application/xml") || type.contains("text/html"))) {
                try {
                    builder.setResponseBody(response.getContentAsString());
                } catch (final IOException exception) {
                    logger.error(
                      "There was an error while reading the contents of the resource " + "located @ " + url.toString(),
                      exception);
                }
            }
        }
        buffer = new StringBuilder();
        buffer.append("/").append(version).append("/Accounts/");
        buffer.append(accountId.toString()).append("/Notifications/");
        buffer.append(sid.toString());
        final URI uri = URI.create(buffer.toString());
        builder.setUri(uri);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onReceive(final Object message) throws Exception {
        final Class<?> klass = message.getClass();
        final State state = fsm.state();
        if (StartInterpreter.class.equals(klass)) {
            fsm.transition(message, acquiringLastGeolocationRequest);
        } else if (GeolocationSessionRequest.class.equals(klass)) {
            customRequestHeaderMap = ((GeolocationSessionRequest)message).headers();
            fsm.transition(message, downloadingRcml);
        } else if (DownloaderResponse.class.equals(klass)) {
            final DownloaderResponse response = (DownloaderResponse) message;
            if (response.succeeded()) {
                final HttpResponseDescriptor descriptor = response.get();
                if (HttpStatus.SC_OK == descriptor.getStatusCode()) {
                    fsm.transition(message, ready);
                } else {
                    if (downloadingRcml.equals(state)) {
                        if (fallbackUrl != null) {
                            fsm.transition(message, downloadingFallbackRcml);
                        }
                    } else {
                        if (sessions.size() > 0) {
                            fsm.transition(message, waitingForGeolocationResponses);
                        } else {
                            fsm.transition(message, finished);
                        }
                    }
                }
            } else {
                if (downloadingRcml.equals(state)) {
                    if (fallbackUrl != null) {
                        fsm.transition(message, downloadingFallbackRcml);
                    }
                } else {
                    if (sessions.size() > 0) {
                        fsm.transition(message, waitingForGeolocationResponses);
                    } else {
                        fsm.transition(message, finished);
                    }
                }
            }
        }  else if (ParserFailed.class.equals(klass)) {
            if(logger.isInfoEnabled()) {
                logger.info("ParserFailed received. Will stop the call");
            }
            fsm.transition(message, finished);
        } else if (Tag.class.equals(klass)) {
            final Tag verb = (Tag) message;
            if (Verbs.redirect.equals(verb.name())) {
                fsm.transition(message, redirecting);
            } else if (Verbs.geolocation.equals(verb.name())) {
                fsm.transition(message, creatingGeolocationSession);
            } else if (Verbs.email.equals(verb.name())) {
                fsm.transition(message, sendingEmail);
            } else {
                invalidVerb(verb);
            }
        } else if (GeolocationServiceResponse.class.equals(klass)) {
            final GeolocationServiceResponse<ActorRef> response = (GeolocationServiceResponse<ActorRef>) message;
            if (response.succeeded()) {
                if (creatingGeolocationSession.equals(state)) {
                    fsm.transition(message, sendingGeolocation);
                }
            } else {
                if (sessions.size() > 0) {
                    fsm.transition(message, waitingForGeolocationResponses);
                } else {
                    fsm.transition(message, finished);
                }
            }
        } else if (GeolocationSessionResponse.class.equals(klass)) {
            response(message);
        } else if (StopInterpreter.class.equals(klass)) {
            if (sessions.size() > 0) {
                fsm.transition(message, waitingForGeolocationResponses);
            } else {
                fsm.transition(message, finished);
            }
        } else if (EmailResponse.class.equals(klass)) {
            final EmailResponse response = (EmailResponse) message;
            if (!response.succeeded()) {
                logger.error(
                  "There was an error while sending an email :" + response.error(),
                  response.cause());
            }
            fsm.transition(message, ready);
        }
    }

    protected List<NameValuePair> parameters() {
        final List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        final String GeolocationSessionSid = initialSessionSid.toString();
        parameters.add(new BasicNameValuePair("GeolocationSid", GeolocationSessionSid));
        final String accountSid = accountId.toString();
        parameters.add(new BasicNameValuePair("AccountSid", accountSid));
        final String from = format(initialSessionRequest.from());
        parameters.add(new BasicNameValuePair("From", from));
        final String to = format(initialSessionRequest.to());
        parameters.add(new BasicNameValuePair("To", to));
        final String body = initialSessionRequest.body();
        parameters.add(new BasicNameValuePair("Body", body));

        //Issue https://telestax.atlassian.net/browse/RESTCOMM-517. If Request contains custom headers pass them to the HTTP server.
        if(customRequestHeaderMap != null && !customRequestHeaderMap.isEmpty()){
            Iterator<String> iter = customRequestHeaderMap.keySet().iterator();
            while(iter.hasNext()){
                String headerName = iter.next();
                parameters.add(new BasicNameValuePair("SipHeader_" + headerName, customRequestHeaderMap.remove(headerName)));
            }
        }
        return parameters;
    }

    private ActorRef parser(final String xml) {
        final UntypedActorContext context = getContext();
        return context.actorOf(new Props(new UntypedActorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public UntypedActor create() throws Exception {
                return new Parser(xml, self());
            }
        }));
    }

    private void response(final Object message) {
        final Class<?> klass = message.getClass();
        final ActorRef self = self();
        if (GeolocationSessionResponse.class.equals(klass)) {
            final GeolocationSessionResponse response = (GeolocationSessionResponse) message;
            final GeolocationSessionInfo info = response.info();
            // For future, no records yet for Geolocation at this stage
//            Geolocation record = (Geolocation) info.attributes().get("record");
//            if (response.succeeded()) {
//                final DateTime now = DateTime.now();
//                record = record.setDateSent(now);
//                record = record.setStatus(Status.SENT);
//            } else {
//                record = record.setStatus(Status.FAILED);
//            }
            final GeolocationDao messages = storage.getGeolocationDao();
//            messages.updateGeolocation(record);

            // Notify the callback listener.
            final Object attribute = info.attributes().get("callback");
            if (attribute != null) {
                final URI callback = (URI) attribute;
                final List<NameValuePair> parameters = parameters();
                request = new HttpRequestDescriptor(callback, "POST", parameters);
                downloader.tell(request, null);
            }
            // Destroy the Geolocation session.
            // final ActorRef session = sessions.remove(record.getSid());
            final ActorRef session = sessions.remove(response);
            final DestroyGeolocationSession destroy = new DestroyGeolocationSession(session);
            service.tell(destroy, self);
            // Try to stop the interpreter.
            final State state = fsm.state();
            if (waitingForGeolocationResponses.equals(state)) {
                final StopInterpreter stop = new StopInterpreter();
                self.tell(stop, self);
            }
        }
    }

    protected URI resolve(final URI base, final URI uri) {
        if (base.equals(uri)) {
            return uri;
        } else {
            if (!uri.isAbsolute()) {
                return base.resolve(uri);
            } else {
                return uri;
            }
        }
    }

    private abstract class AbstractAction implements Action {
        protected final ActorRef source;

        public AbstractAction(final ActorRef source) {
            super();
            this.source = source;
        }
    }

    private final class AcquiringLastGeolocationEvent extends AbstractAction {
        public AcquiringLastGeolocationEvent(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final StartInterpreter request = (StartInterpreter) message;
            initialSession = request.resource();
            initialSession.tell(new Observe(source), source);
            initialSession.tell(new GetLastGeolocationRequest(), source);
        }
    }

    private final class DownloadingRcml extends AbstractAction {
        public DownloadingRcml(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            initialSessionRequest = (GeolocationSessionRequest) message;
            initialSessionSid = Sid.generate(Sid.Type.GEOLOCATION);
            final Geolocation.Builder builder = Geolocation.builder();
            builder.setSid(initialSessionSid);
            builder.setAccountSid(accountId);
            builder.setApiVersion(version);
            // For future, no SIP or charging used yet
//            builder.setRecipient(initialSessionRequest.to());
//            builder.setSender(initialSessionRequest.from());
//            builder.setBody(initialSessionRequest.body());
//            builder.setDirection(Direction.INBOUND);
//            builder.setStatus(Status.RECEIVED);
//            builder.setPrice(new BigDecimal("0.00"));
            // TODO implement currency property to be read from Configuration
//            builder.setPriceUnit(Currency.getInstance("USD"));
            final StringBuilder buffer = new StringBuilder();
            buffer.append("/").append(version).append("/Accounts/");
            buffer.append(accountId.toString()).append("/Geolocations/");
            buffer.append(initialSessionSid.toString());
            final URI uri = URI.create(buffer.toString());
            builder.setUri(uri);
            final Geolocation record = builder.build();
            final GeolocationDao messages = storage.getGeolocationDao();
            messages.addGeolocation(record);
            // Destroy the initial session.
            service.tell(new DestroyGeolocationSession(initialSession), source);
            initialSession = null;
            // Ask the downloader to get us the application that will be executed.
            final List<NameValuePair> parameters = parameters();
            request = new HttpRequestDescriptor(url, method, parameters);
            downloader.tell(request, source);
        }
    }

    private final class DownloadingFallbackRcml extends AbstractAction {
        public DownloadingFallbackRcml(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final Class<?> klass = message.getClass();
            // Notify the account of the issue.
            if (DownloaderResponse.class.equals(klass)) {
                final DownloaderResponse result = (DownloaderResponse) message;
                final Throwable cause = result.cause();
                Notification notification = null;
                if (cause instanceof ClientProtocolException) {
                    notification = notification(ERROR_NOTIFICATION, 11206, cause.getMessage());
                } else if (cause instanceof IOException) {
                    notification = notification(ERROR_NOTIFICATION, 11205, cause.getMessage());
                } else if (cause instanceof URISyntaxException) {
                    notification = notification(ERROR_NOTIFICATION, 11100, cause.getMessage());
                }
                if (notification != null) {
                    final NotificationsDao notifications = storage.getNotificationsDao();
                    notifications.addNotification(notification);
                }
            }
            // Try to use the fall back url and method.
            final List<NameValuePair> parameters = parameters();
            request = new HttpRequestDescriptor(fallbackUrl, fallbackMethod, parameters);
            downloader.tell(request, source);
        }
    }

    private final class Ready extends AbstractAction {
        public Ready(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final UntypedActorContext context = getContext();
            final State state = fsm.state();
            // Make sure we create a new parser if necessary.
            if (downloadingRcml.equals(state) || downloadingFallbackRcml.equals(state) || redirecting.equals(state)
              || sendingGeolocation.equals(state)) {
                response = ((DownloaderResponse) message).get();
                if (parser != null) {
                    context.stop(parser);
                    parser = null;
                }
                try{
                    final String type = response.getContentType();
                    final String content = response.getContentAsString();
                    if ((type != null && content != null) && (type.contains("text/xml") || type.contains("application/xml") || type.contains("text/html"))) {
                        parser = parser(content);
                    } else {
                        if(logger.isInfoEnabled()) {
                            logger.info("DownloaderResponse getContentType is null: "+response);
                        }
                        final NotificationsDao notifications = storage.getNotificationsDao();
                        final Notification notification = notification(WARNING_NOTIFICATION, 12300, "Invalide content-type.");
                        notifications.addNotification(notification);
                        final StopInterpreter stop = new StopInterpreter();
                        source.tell(stop, source);
                        return;
                    }
                } catch (Exception e) {
                    final NotificationsDao notifications = storage.getNotificationsDao();
                    final Notification notification = notification(WARNING_NOTIFICATION, 12300, "Invalid content-type.");
                    notifications.addNotification(notification);
                    final StopInterpreter stop = new StopInterpreter();
                    source.tell(stop, source);
                    return;
                }
            }
            // Ask the parser for the next action to take.
            Header[] headers = response.getHeaders();
            for(Header header: headers) {
                if (header.getName().startsWith("X-")) {
                    customHttpHeaderMap.put(header.getName(), header.getValue());
                }
            }
            final GetNextVerb next = GetNextVerb.instance();
            parser.tell(next, source);
        }
    }

    private final class Redirecting extends AbstractAction {
        public Redirecting(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            verb = (Tag) message;
            final NotificationsDao notifications = storage.getNotificationsDao();
            String method = "POST";
            Attribute attribute = verb.attribute("method");
            if (attribute != null) {
                method = attribute.value();
                if (method != null && !method.isEmpty()) {
                    if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                        final Notification notification = notification(WARNING_NOTIFICATION, 13710, method
                          + " is not a valid HTTP method for <Redirect>");
                        notifications.addNotification(notification);
                        method = "POST";
                    }
                } else {
                    method = "POST";
                }
            }
            final String text = verb.text();
            if (text != null && !text.isEmpty()) {
                // Try to redirect.
                URI target = null;
                try {
                    target = URI.create(text);
                } catch (final Exception exception) {
                    final Notification notification = notification(ERROR_NOTIFICATION, 11100, text + " is an invalid URI.");
                    notifications.addNotification(notification);
                    final StopInterpreter stop = new StopInterpreter();
                    source.tell(stop, source);
                    return;
                }
                final URI base = request.getUri();
                final URI uri = resolve(base, target);
                final List<NameValuePair> parameters = parameters();
                request = new HttpRequestDescriptor(uri, method, parameters);
                downloader.tell(request, source);
            } else {
                // Ask the parser for the next action to take.
                final GetNextVerb next = GetNextVerb.instance();
                parser.tell(next, source);
            }
        }
    }

    private final class CreatingGeolocationSession extends AbstractAction {
        public CreatingGeolocationSession(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            // Save <Geolocation> verb.
            verb = (Tag) message;
            // Create a new Geolocation session to handle the <Geolocation> verb.
            service.tell(new CreateGeolocationSession(initialSessionRequest.from(), initialSessionRequest.to(), accountId.toString(), false), source);
        }
    }

    private final class SendingGeolocation extends AbstractAction {
        public SendingGeolocation(final ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final GeolocationServiceResponse<ActorRef> response = (GeolocationServiceResponse<ActorRef>) message;
            final ActorRef session = response.get();
            final NotificationsDao notifications = storage.getNotificationsDao();
            // Parse "from".
            String from = initialSessionRequest.to();
            Attribute attribute = verb.attribute("from");
            if (attribute != null) {
                from = attribute.value();
                if (from != null && !from.isEmpty()) {
                    from = format(from);
                    if (from == null) {
                        from = verb.attribute("from").value();
                        final Notification notification = notification(ERROR_NOTIFICATION, 14102, from
                          + " is an invalid 'from' phone number.");
                        notifications.addNotification(notification);
                        service.tell(new DestroyGeolocationSession(session), source);
                        final StopInterpreter stop = new StopInterpreter();
                        source.tell(stop, source);
                        return;
                    }
                } else {
                    from = initialSessionRequest.to();
                }
            }
            // Parse "to".
            String to = initialSessionRequest.from();
            attribute = verb.attribute("to");
            if (attribute != null) {
                to = attribute.value();
                if (to == null) {
                    to = initialSessionRequest.from();
                }
                //                if (to != null && !to.isEmpty()) {
                //                    to = format(to);
                //                    if (to == null) {
                //                        to = verb.attribute("to").value();
                //                        final Notification notification = notification(ERROR_NOTIFICATION, 14101, to
                //                                + " is an invalid 'to' phone number.");
                //                        notifications.addNotification(notification);
                //                        service.tell(new DestroyGeolocationSession(session), source);
                //                        final StopInterpreter stop = StopInterpreter.instance();
                //                        source.tell(stop, source);
                //                        return;
                //                    }
                //                } else {
                //                    to = initialSessionRequest.from();
                //                }
            }
            // Parse <Geolocation> text.
            String body = verb.text();
            if (body == null || body.isEmpty()) {
                final Notification notification = notification(ERROR_NOTIFICATION, 14103, body + " is an invalid Geolocation body.");
                notifications.addNotification(notification);
                service.tell(new DestroyGeolocationSession(session), source);
                final StopInterpreter stop = new StopInterpreter();
                source.tell(stop, source);
                return;
            } else {
                // Start observing events from the Geolocation session.
                session.tell(new Observe(source), source);
                // Store the status callback in the Geolocation session.
                attribute = verb.attribute("statusCallback");
                if (attribute != null) {
                    String callback = attribute.value();
                    if (callback != null && !callback.isEmpty()) {
                        URI target = null;
                        try {
                            target = URI.create(callback);
                        } catch (final Exception exception) {
                            final Notification notification = notification(ERROR_NOTIFICATION, 14105, callback
                              + " is an invalid URI.");
                            notifications.addNotification(notification);
                            service.tell(new DestroyGeolocationSession(session), source);
                            final StopInterpreter stop = new StopInterpreter();
                            source.tell(stop, source);
                            return;
                        }
                        final URI base = request.getUri();
                        final URI uri = resolve(base, target);
                        session.tell(new GeolocationSessionAttribute("callback", uri), source);
                    }
                }
                // Create a Geolocation detail record.
                final Sid sid = Sid.generate(Sid.Type.GEOLOCATION);
                final Geolocation.Builder builder = Geolocation.builder();
                builder.setSid(sid);
                builder.setAccountSid(accountId);
                builder.setApiVersion(version);
                /* For future when SIP is used
                builder.setRecipient(to);
                builder.setSender(from);
                builder.setBody(body);
                builder.setDirection(Direction.OUTBOUND_REPLY);
                builder.setStatus(Status.RECEIVED);
                builder.setPrice(new BigDecimal("0.00"));*/
                // TODO implement currency property to be read from Configuration
                // builder.setPriceUnit(Currency.getInstance("USD"));
                final StringBuilder buffer = new StringBuilder();
                buffer.append("/").append(version).append("/Accounts/");
                buffer.append(accountId.toString()).append("/Geolocation/Events/");
                buffer.append(sid.toString());
                final URI uri = URI.create(buffer.toString());
                builder.setUri(uri);
                final Geolocation record = builder.build();
                final GeolocationDao messages = storage.getGeolocationDao();
                messages.addGeolocation(record);
                // Store the Geolocation record in the Geolocation session.
                session.tell(new GeolocationSessionAttribute("record", record), source);
                // Send the Geolocation.
                final GeolocationSessionRequest Geolocation = new GeolocationSessionRequest(from, to, body, null, null, null, customHttpHeaderMap);
                session.tell(Geolocation, source);
                sessions.put(sid, session);
            }
            // Parses "action".
            attribute = verb.attribute("action");
            if (attribute != null) {
                String action = attribute.value();
                if (action != null && !action.isEmpty()) {
                    URI target = null;
                    try {
                        target = URI.create(action);
                    } catch (final Exception exception) {
                        final Notification notification = notification(ERROR_NOTIFICATION, 11100, action
                          + " is an invalid URI.");
                        notifications.addNotification(notification);
                        final StopInterpreter stop = new StopInterpreter();
                        source.tell(stop, source);
                        return;
                    }
                    final URI base = request.getUri();
                    final URI uri = resolve(base, target);
                    // Parse "method".
                    String method = "POST";
                    attribute = verb.attribute("method");
                    if (attribute != null) {
                        method = attribute.value();
                        if (method != null && !method.isEmpty()) {
                            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                                final Notification notification = notification(WARNING_NOTIFICATION, 14104, method
                                  + " is not a valid HTTP method for <Geolocation>");
                                notifications.addNotification(notification);
                                method = "POST";
                            }
                        } else {
                            method = "POST";
                        }
                    }
                    // Redirect to the action url.
                    final List<NameValuePair> parameters = parameters();
                    final String status = "processing"; //FIXME
                    parameters.add(new BasicNameValuePair("GeolocationStatus", status));
                    request = new HttpRequestDescriptor(uri, method, parameters);
                    downloader.tell(request, source);
                    return;
                }
            }
            // Ask the parser for the next action to take.
            final GetNextVerb next = GetNextVerb.instance();
            parser.tell(next, source);
        }
    }

    private final class WaitingForGeolocationResponses extends AbstractAction {
        public WaitingForGeolocationResponses(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            response(message);
        }
    }

    private final class Finished extends AbstractAction {
        public Finished(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final UntypedActorContext context = getContext();
            context.stop(source);
        }
    }

    private final class SendingEmail extends AbstractAction {
        public SendingEmail(final ActorRef source){
            super(source);
        }

        @Override
        public void execute( final Object message) throws Exception {
            final Tag verb = (Tag)message;
            // Parse "from".
            String from;
            Attribute attribute = verb.attribute("from");
            if (attribute != null) {
                from = attribute.value();
            }else{
                Exception error = new Exception("From attribute was not defined");
                source.tell(new EmailResponse(error,error.getMessage()), source);
                return;
            }

            // Parse "to".
            String to;
            attribute = verb.attribute("to");
            if (attribute != null) {
                to = attribute.value();
            }else{
                Exception error = new Exception("To attribute was not defined");
                source.tell(new EmailResponse(error,error.getMessage()), source);
                return;
            }

            // Parse "cc".
            String cc="";
            attribute = verb.attribute("cc");
            if (attribute != null) {
                cc = attribute.value();
            }

            // Parse "bcc".
            String bcc="";
            attribute = verb.attribute("bcc");
            if (attribute != null) {
                bcc = attribute.value();
            }

            // Parse "subject"
            String subject;
            attribute = verb.attribute("subject");
            if (attribute != null) {
                subject = attribute.value();
            }else{
                subject="Restcomm Email Service";
            }

            // Send the email.
            final Mail emailMsg = new Mail(from, to, subject, verb.text(),cc,bcc);
            if (mailerService == null){
                mailerService = mailer(configuration.subset("smtp-service"));
            }
            mailerService.tell(new EmailRequest(emailMsg), self());
        }
    }

}
