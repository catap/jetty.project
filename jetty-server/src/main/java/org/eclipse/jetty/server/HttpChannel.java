//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.ErrorHandler.ErrorPageMapper;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * HttpChannel represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both a HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
 */
public class HttpChannel implements Runnable, HttpOutput.Interceptor
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);

    private final AtomicLong _requests = new AtomicLong();
    private final Connector _connector;
    private final Executor _executor;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private HttpFields _trailers;
    private final Supplier<HttpFields> _trailerSupplier = () -> _trailers;
    private final List<Listener> _listeners;
    private MetaData.Response _committedMetaData;
    private RequestLog _requestLog;
    private long _oldIdleTimeout;

    /**
     * Bytes written after interception (eg after compression)
     */
    private long _written;

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        _connector = connector;
        _configuration = configuration;
        _endPoint = endPoint;
        _transport = transport;

        _state = new HttpChannelState(this);
        _request = new Request(this, newHttpInput(_state));
        _response = new Response(this, newHttpOutput());

        _executor = connector == null ? null : connector.getServer().getThreadPool();
        _requestLog = connector == null ? null : connector.getServer().getRequestLog();

        List<Listener> listeners = new ArrayList<>();
        if (connector != null)
            listeners.addAll(connector.getBeans(Listener.class));
        _listeners = listeners;

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{},{}",
                this,
                _endPoint,
                _endPoint == null ? null : _endPoint.getConnection(),
                _state);
    }

    public boolean isSendError()
    {
        return _state.isSendError();
    }

    protected HttpInput newHttpInput(HttpChannelState state)
    {
        return new HttpInput(state);
    }

    protected HttpOutput newHttpOutput()
    {
        return new HttpOutput(this);
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    public boolean addListener(Listener listener)
    {
        return _listeners.add(listener);
    }

    public boolean removeListener(Listener listener)
    {
        return _listeners.remove(listener);
    }

    public long getBytesWritten()
    {
        return _written;
    }

    /**
     * @return the number of requests handled by this connection
     */
    public long getRequests()
    {
        return _requests.get();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpTransport getHttpTransport()
    {
        return _transport;
    }

    public RequestLog getRequestLog()
    {
        return _requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        _requestLog = requestLog;
    }

    public void addRequestLog(RequestLog requestLog)
    {
        if (_requestLog == null)
            _requestLog = requestLog;
        else if (_requestLog instanceof RequestLogCollection)
            ((RequestLogCollection)_requestLog).add(requestLog);
        else
            _requestLog = new RequestLogCollection(_requestLog, requestLog);
    }

    public MetaData.Response getCommittedMetaData()
    {
        return _committedMetaData;
    }

    /**
     * Get the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#getIdleTimeout()}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @return the idle timeout (in milliseconds)
     */
    public long getIdleTimeout()
    {
        return _endPoint.getIdleTimeout();
    }

    /**
     * Set the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#setIdleTimeout(long)}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @param timeoutMs the idle timeout in milliseconds
     */
    public void setIdleTimeout(long timeoutMs)
    {
        _endPoint.setIdleTimeout(timeoutMs);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return _connector.getByteBufferPool();
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        return getHttpTransport().isOptimizedForDirectBuffers();
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Request getRequest()
    {
        return _request;
    }

    public Response getResponse()
    {
        return _response;
    }

    public Connection getConnection()
    {
        return _endPoint.getConnection();
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    public InetSocketAddress getLocalAddress()
    {
        return _endPoint.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return _endPoint.getRemoteAddress();
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @param available estimate of the number of bytes that are available
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    public void recycle()
    {
        _request.recycle();
        _response.recycle();
        _committedMetaData = null;
        _requestLog = _connector == null ? null : _connector.getServer().getRequestLog();
        _written = 0;
        _trailers = null;
        _oldIdleTimeout = 0;
    }

    public void onAsyncWaitForContent()
    {
    }

    public void onBlockWaitForContent()
    {
    }

    public void onBlockWaitForContentFailure(Throwable failure)
    {
        getRequest().getHttpInput().failed(failure);
    }

    @Override
    public void run()
    {
        handle();
    }

    /**
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} {} ", _request.getHttpURI(), this);

        HttpChannelState.Action action = _state.handling();

        // Loop here to handle async request redispatches.
        // The loop is controlled by the call to async.unhandle in the
        // finally block below.  Unhandle will return false only if an async dispatch has
        // already happened when unhandle is called.
        loop:
        while (!getServer().isStopped())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("action {} {}", action, this);

                switch (action)
                {
                    case TERMINATED:
                        onCompleted();
                        break loop;

                    case WAIT:
                        // break loop without calling unhandle
                        break loop;

                    case NOOP:
                        // do nothing other than call unhandle
                        break;

                    case DISPATCH:
                    {
                        if (!_request.hasMetaData())
                            throw new IllegalStateException("state=" + _state);
                        _request.setHandled(false);
                        _response.getHttpOutput().reopen();

                        try
                        {
                            _request.setDispatcherType(DispatcherType.REQUEST);
                            notifyBeforeDispatch(_request);

                            List<HttpConfiguration.Customizer> customizers = _configuration.getCustomizers();
                            if (!customizers.isEmpty())
                            {
                                for (HttpConfiguration.Customizer customizer : customizers)
                                {
                                    customizer.customize(getConnector(), _configuration, _request);
                                    if (_request.isHandled())
                                        break;
                                }
                            }

                            if (!_request.isHandled())
                                getServer().handle(this);
                        }
                        catch (Throwable x)
                        {
                            notifyDispatchFailure(_request, x);
                            throw x;
                        }
                        finally
                        {
                            notifyAfterDispatch(_request);
                            _request.setDispatcherType(null);
                        }
                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        _request.setHandled(false);
                        _response.getHttpOutput().reopen();

                        try
                        {
                            _request.setDispatcherType(DispatcherType.ASYNC);
                            notifyBeforeDispatch(_request);
                            getServer().handleAsync(this);
                        }
                        catch (Throwable x)
                        {
                            notifyDispatchFailure(_request, x);
                            throw x;
                        }
                        finally
                        {
                            notifyAfterDispatch(_request);
                            _request.setDispatcherType(null);
                        }
                        break;
                    }

                    case ASYNC_TIMEOUT:
                        _state.onTimeout();
                        break;

                    case ERROR_DISPATCH:
                    {
                        try
                        {
                            // the following is needed as you cannot trust the response code and reason
                            // as those could have been modified after calling sendError
                            Integer icode = (Integer)_request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                            int code = icode != null ? icode : HttpStatus.INTERNAL_SERVER_ERROR_500;
                            _request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, code);
                            _response.setStatus(code);

                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();

                            ContextHandler.Context context = (ContextHandler.Context)_request.getAttribute(ErrorHandler.ERROR_CONTEXT);
                            ErrorHandler errorHandler = ErrorHandler.getErrorHandler(getServer(), context == null ? null : context.getContextHandler());

                            // If we can't have a body, then create a minimal error response.
                            if (HttpStatus.hasNoBody(code) ||
                                errorHandler == null ||
                                !errorHandler.errorPageForMethod(_request.getMethod()))
                            {
                                _request.setHandled(true);
                                minimalErrorResponse(code);
                                break;
                            }

                            // Look for an error page
                            String errorPage = (errorHandler instanceof ErrorPageMapper) ? ((ErrorPageMapper)errorHandler).getErrorPage(_request) : null;
                            Dispatcher errorDispatcher = errorPage != null ? (Dispatcher)context.getRequestDispatcher(errorPage) : null;

                            if (errorDispatcher != null)
                            {
                                try
                                {
                                    _request.setAttribute(ErrorHandler.ERROR_PAGE, errorPage);
                                    _request.setDispatcherType(DispatcherType.ERROR);
                                    notifyBeforeDispatch(_request);
                                    errorDispatcher.error(_request, _response);
                                    break;
                                }
                                catch (Throwable x)
                                {
                                    notifyDispatchFailure(_request, x);
                                    throw x;
                                }
                                finally
                                {
                                    notifyAfterDispatch(_request);
                                    _request.setDispatcherType(null);
                                }
                            }
                            else
                            {
                                // Allow ErrorHandler to generate response
                                errorHandler.handle(null, _request, _request, _response);
                                _request.setHandled(true);
                            }
                        }
                        catch (Throwable x)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform ERROR dispatch, aborting", x);
                            Throwable failure = (Throwable)_request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                            if (failure == null)
                                failure = x;
                            else
                                failure.addSuppressed(x);

                            Throwable cause = unwrap(failure, BadMessageException.class);
                            int code = cause instanceof BadMessageException ? ((BadMessageException)cause).getCode() : 500;

                            if (!_state.isResponseCommitted())
                                minimalErrorResponse(code);
                        }
                        finally
                        {
                            // clean up the context that was set in Response.sendError
                            _request.removeAttribute(ErrorHandler.ERROR_CONTEXT);
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_REGISTER:
                    {
                        onAsyncWaitForContent();
                    }

                    case READ_PRODUCE:
                    {
                        _request.getHttpInput().asyncReadProduce();
                        break;
                    }

                    case READ_CALLBACK:
                    {
                        ContextHandler handler = _state.getContextHandler();
                        if (handler != null)
                            handler.handle(_request, _request.getHttpInput());
                        else
                            _request.getHttpInput().run();
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        ContextHandler handler = _state.getContextHandler();
                        if (handler != null)
                            handler.handle(_request, _response.getHttpOutput());
                        else
                            _response.getHttpOutput().run();
                        break;
                    }

                    case COMPLETE:
                    {
                        if (!_response.isCommitted() && !_request.isHandled() && !_response.getHttpOutput().isClosed())
                        {
                            _response.sendError(HttpStatus.NOT_FOUND_404);
                            break;
                        }

                        // RFC 7230, section 3.3.
                        if (!_request.isHead() && !_response.isContentComplete(_response.getHttpOutput().getWritten()))
                        {
                            if (isCommitted())
                                abort(new IOException("insufficient content written"));
                            else
                            {
                                _response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500, "insufficient content written");
                                break;
                            }
                        }
                        _response.closeOutput(); // TODO make this non blocking!
                        _state.completed();
                        break;
                    }

                    default:
                    {
                        throw new IllegalStateException(this.toString());
                    }
                }
            }
            catch (Throwable failure)
            {
                if ("org.eclipse.jetty.continuation.ContinuationThrowable".equals(failure.getClass().getName()))
                    LOG.ignore(failure);
                else
                    handleException(failure);
            }

            action = _state.unhandle();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("!handle {} {}", action, this);

        boolean suspended = action == Action.WAIT;
        return !suspended;
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param failure the Throwable that caused the problem
     */
    protected void handleException(Throwable failure)
    {
        // Unwrap wrapping Jetty and Servlet exceptions.
        Throwable quiet = unwrap(failure, QuietException.class);
        Throwable noStack = unwrap(failure, BadMessageException.class, IOException.class, TimeoutException.class);

        if (quiet != null || !getServer().isRunning())
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_request.getRequestURI(), failure);
        }
        else if (noStack != null)
        {
            // No stack trace unless there is debug turned on
            if (LOG.isDebugEnabled())
                LOG.warn(_request.getRequestURI(), failure);
            else
                LOG.warn("{} {}", _request.getRequestURI(), noStack.toString());
        }
        else
        {
            LOG.warn(_request.getRequestURI(), failure);
        }

        if (isCommitted())
            abort(failure);
        else
            _state.thrownException(failure);
    }

    /**
     * Unwrap failure causes to find target class
     *
     * @param failure The throwable to have its causes unwrapped
     * @param targets Exception classes that we should not unwrap
     * @return A target throwable or null
     */
    protected Throwable unwrap(Throwable failure, Class<?>... targets)
    {
        while (failure != null)
        {
            for (Class<?> x : targets)
            {
                if (x.isInstance(failure))
                    return failure;
            }
            failure = failure.getCause();
        }
        return null;
    }

    private void minimalErrorResponse(int code)
    {
        try
        {
            _response.resetContent();
            _response.setStatus(code);
            _request.setHandled(true);

            // TODO use the non blocking version
            sendResponse(null, null, true);
        }
        catch (Throwable x)
        {
            abort(x);
        }
    }

    public boolean isExpecting100Continue()
    {
        return false;
    }

    public boolean isExpecting102Processing()
    {
        return false;
    }

    @Override
    public String toString()
    {
        long timeStamp = _request.getTimeStamp();
        return String.format("%s@%x{s=%s,r=%s,c=%b/%b,a=%s,uri=%s,age=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _state,
            _requests,
            isRequestCompleted(),
            isResponseCompleted(),
            _state.getState(),
            _request.getHttpURI(),
            timeStamp == 0 ? 0 : System.currentTimeMillis() - timeStamp);
    }

    public void onRequest(MetaData.Request request)
    {
        _requests.incrementAndGet();
        _request.setTimeStamp(System.currentTimeMillis());
        HttpFields fields = _response.getHttpFields();
        if (_configuration.getSendDateHeader() && !fields.contains(HttpHeader.DATE))
            fields.put(_connector.getServer().getDateField());

        long idleTO = _configuration.getIdleTimeout();
        _oldIdleTimeout = getIdleTimeout();
        if (idleTO >= 0 && _oldIdleTimeout != idleTO)
            setIdleTimeout(idleTO);

        request.setTrailerSupplier(_trailerSupplier);
        _request.setMetaData(request);

        _request.setSecure(HttpScheme.HTTPS.is(request.getURI().getScheme()));

        notifyRequestBegin(_request);

        if (LOG.isDebugEnabled())
            LOG.debug("REQUEST for {} on {}{}{} {} {}{}{}", request.getURIString(), this, System.lineSeparator(),
                request.getMethod(), request.getURIString(), request.getHttpVersion(), System.lineSeparator(),
                request.getFields());
    }

    public boolean onContent(HttpInput.Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onContent {} {}", this, content);
        notifyRequestContent(_request, content.getByteBuffer());
        return _request.getHttpInput().addContent(content);
    }

    public boolean onContentComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onContentComplete {}", this);
        notifyRequestContentEnd(_request);
        return false;
    }

    public void onTrailers(HttpFields trailers)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onTrailers {} {}", this, trailers);
        _trailers = trailers;
        notifyRequestTrailers(_request);
    }

    public boolean onRequestComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onRequestComplete {}", this);
        boolean result = _request.getHttpInput().eof();
        notifyRequestEnd(_request);
        return result;
    }

    public void onCompleted()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("COMPLETE for {} written={}", getRequest().getRequestURI(), getBytesWritten());

        if (_requestLog != null)
            _requestLog.log(_request, _response);

        long idleTO = _configuration.getIdleTimeout();
        if (idleTO >= 0 && getIdleTimeout() != _oldIdleTimeout)
            setIdleTimeout(_oldIdleTimeout);

        notifyComplete(_request);

        _transport.onCompleted();
    }

    public boolean onEarlyEOF()
    {
        return _request.getHttpInput().earlyEOF();
    }

    public void onBadMessage(BadMessageException failure)
    {
        int status = failure.getCode();
        String reason = failure.getReason();
        if (status < 400 || status > 599)
            failure = new BadMessageException(HttpStatus.BAD_REQUEST_400, reason, failure);

        notifyRequestFailure(_request, failure);

        Action action;
        try
        {
            action = _state.handling();
        }
        catch (Throwable e)
        {
            // The bad message cannot be handled in the current state,
            // so rethrow, hopefully somebody will be able to handle.
            abort(e);
            throw failure;
        }

        try
        {
            if (action == Action.DISPATCH)
            {
                ByteBuffer content = null;
                HttpFields fields = new HttpFields();

                ErrorHandler handler = getServer().getBean(ErrorHandler.class);
                if (handler != null)
                    content = handler.badMessageError(status, reason, fields);

                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1, status, reason, fields, BufferUtil.length(content)), content, true);
            }
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
        finally
        {
            try
            {
                onCompleted();
            }
            catch (Throwable e)
            {
                LOG.debug(e);
                abort(e);
            }
        }
    }

    public boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete, final Callback callback)
    {
        boolean committing = _state.commitResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("sendResponse info={} content={} complete={} committing={} callback={}",
                info,
                BufferUtil.toDetailString(content),
                complete,
                committing,
                callback);

        if (committing)
        {
            // We need an info to commit
            if (info == null)
                info = _response.newResponseMetaData();
            commit(info);

            // wrap callback to process 100 responses
            final int status = info.getStatus();
            final Callback committed = (status < 200 && status >= 100) ? new Send100Callback(callback) : new SendCallback(callback, content, true, complete);

            notifyResponseBegin(_request);

            // committing write
            _transport.send(info, _request.isHead(), content, complete, committed);
        }
        else if (info == null)
        {
            // This is a normal write
            _transport.send(null, _request.isHead(), content, complete, new SendCallback(callback, content, false, complete));
        }
        else
        {
            callback.failed(new IllegalStateException("committed"));
        }
        return committing;
    }

    public boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker blocker = _response.getHttpOutput().acquireWriteBlockingCallback())
        {
            boolean committing = sendResponse(info, content, complete, blocker);
            blocker.block();
            return committing;
        }
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            throw failure;
        }
    }

    protected void commit(MetaData.Response info)
    {
        _committedMetaData = info;
        if (LOG.isDebugEnabled())
            LOG.debug("COMMIT for {} on {}{}{} {} {}{}{}", getRequest().getRequestURI(), this, System.lineSeparator(),
                info.getStatus(), info.getReason(), info.getHttpVersion(), System.lineSeparator(),
                info.getFields());
    }

    public boolean isCommitted()
    {
        return _state.isResponseCommitted();
    }

    /**
     * @return True if the request lifecycle is completed
     */
    public boolean isRequestCompleted()
    {
        return _state.isCompleted();
    }

    /**
     * @return True if the response is completely written.
     */
    public boolean isResponseCompleted()
    {
        return _state.isResponseCompleted();
    }

    public boolean isPersistent()
    {
        return _endPoint.isOpen();
    }

    /**
     * <p>Non-Blocking write, committing the response if needed.</p>
     * Called as last link in HttpOutput.Filter chain
     *
     * @param content the content buffer to write
     * @param complete whether the content is complete for the response
     * @param callback Callback when complete or failed
     */
    @Override
    public void write(ByteBuffer content, boolean complete, Callback callback)
    {
        sendResponse(null, content, complete, callback);
    }

    @Override
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
    }

    @Override
    public HttpOutput.Interceptor getNextInterceptor()
    {
        return null;
    }

    protected void execute(Runnable task)
    {
        _executor.execute(task);
    }

    public Scheduler getScheduler()
    {
        return _connector.getScheduler();
    }

    /**
     * @return true if the HttpChannel can efficiently use direct buffer (typically this means it is not over SSL or a multiplexed protocol)
     */
    public boolean useDirectBuffers()
    {
        return getEndPoint() instanceof ChannelEndPoint;
    }

    /**
     * If a write or similar operation to this channel fails,
     * then this method should be called.
     * <p>
     * The standard implementation calls {@link HttpTransport#abort(Throwable)}.
     *
     * @param failure the failure that caused the abort.
     */
    public void abort(Throwable failure)
    {
        if (_state.abortResponse())
        {
            notifyResponseFailure(_request, failure);
            _transport.abort(failure);
        }
    }

    private void notifyRequestBegin(Request request)
    {
        notifyEvent1(listener -> listener::onRequestBegin, request);
    }

    private void notifyBeforeDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onBeforeDispatch, request);
    }

    private void notifyDispatchFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onDispatchFailure, request, failure);
    }

    private void notifyAfterDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onAfterDispatch, request);
    }

    private void notifyRequestContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onRequestContent, request, content);
    }

    private void notifyRequestContentEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestContentEnd, request);
    }

    private void notifyRequestTrailers(Request request)
    {
        notifyEvent1(listener -> listener::onRequestTrailers, request);
    }

    private void notifyRequestEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestEnd, request);
    }

    private void notifyRequestFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onRequestFailure, request, failure);
    }

    private void notifyResponseBegin(Request request)
    {
        notifyEvent1(listener -> listener::onResponseBegin, request);
    }

    private void notifyResponseCommit(Request request)
    {
        notifyEvent1(listener -> listener::onResponseCommit, request);
    }

    private void notifyResponseContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onResponseContent, request, content);
    }

    private void notifyResponseEnd(Request request)
    {
        notifyEvent1(listener -> listener::onResponseEnd, request);
    }

    private void notifyResponseFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onResponseFailure, request, failure);
    }

    private void notifyComplete(Request request)
    {
        notifyEvent1(listener -> listener::onComplete, request);
    }

    private void notifyEvent1(Function<Listener, Consumer<Request>> function, Request request)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, ByteBuffer>> function, Request request, ByteBuffer content)
    {
        for (Listener listener : _listeners)
        {
            ByteBuffer view = content.slice();
            try
            {
                function.apply(listener).accept(request, view);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, Throwable>> function, Request request, Throwable failure)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request, failure);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    /**
     * <p>Listener for {@link HttpChannel} events.</p>
     * <p>HttpChannel will emit events for the various phases it goes through while
     * processing a HTTP request and response.</p>
     * <p>Implementations of this interface may listen to those events to track
     * timing and/or other values such as request URI, etc.</p>
     * <p>The events parameters, especially the {@link Request} object, may be
     * in a transient state depending on the event, and not all properties/features
     * of the parameters may be available inside a listener method.</p>
     * <p>It is recommended that the event parameters are <em>not</em> acted upon
     * in the listener methods, or undefined behavior may result. For example, it
     * would be a bad idea to try to read some content from the
     * {@link javax.servlet.ServletInputStream} in listener methods. On the other
     * hand, it is legit to store request attributes in one listener method that
     * may be possibly retrieved in another listener method in a later event.</p>
     * <p>Listener methods are invoked synchronously from the thread that is
     * performing the request processing, and they should not call blocking code
     * (otherwise the request processing will be blocked as well).</p>
     */
    public interface Listener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestBegin(Request request)
        {
        }

        /**
         * Invoked just before calling the application.
         *
         * @param request the request object
         */
        default void onBeforeDispatch(Request request)
        {
        }

        /**
         * Invoked when the application threw an exception.
         *
         * @param request the request object
         * @param failure the exception thrown by the application
         */
        default void onDispatchFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just after the application returns from the first invocation.
         *
         * @param request the request object
         */
        default void onAfterDispatch(Request request)
        {
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the request content chunk
         */
        default void onRequestContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the end of the request content is detected.
         *
         * @param request the request object
         */
        default void onRequestContentEnd(Request request)
        {
        }

        /**
         * Invoked when the request trailers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestTrailers(Request request)
        {
        }

        /**
         * Invoked when the request has been fully parsed.
         *
         * @param request the request object
         */
        default void onRequestEnd(Request request)
        {
        }

        /**
         * Invoked when the request processing failed.
         *
         * @param request the request object
         * @param failure the request failure
         */
        default void onRequestFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just before the response line is written to the network.
         *
         * @param request the request object
         */
        default void onResponseBegin(Request request)
        {
        }

        /**
         * Invoked just after the response is committed (that is, the response
         * line, headers and possibly some content have been written to the
         * network).
         *
         * @param request the request object
         */
        default void onResponseCommit(Request request)
        {
        }

        /**
         * Invoked after a response content chunk has been written to the network.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the response content chunk
         */
        default void onResponseContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the response has been fully written.
         *
         * @param request the request object
         */
        default void onResponseEnd(Request request)
        {
        }

        /**
         * Invoked when the response processing failed.
         *
         * @param request the request object
         * @param failure the response failure
         */
        default void onResponseFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete.
         *
         * @param request the request object
         */
        default void onComplete(Request request)
        {
        }
    }

    private class SendCallback extends Callback.Nested
    {
        private final ByteBuffer _content;
        private final int _length;
        private final boolean _commit;
        private final boolean _complete;

        private SendCallback(Callback callback, ByteBuffer content, boolean commit, boolean complete)
        {
            super(callback);
            _content = content == null ? BufferUtil.EMPTY_BUFFER : content.slice();
            _length = _content.remaining();
            _commit = commit;
            _complete = complete;
        }

        @Override
        public void succeeded()
        {
            _written += _length;
            if (_complete)
                _response.getHttpOutput().closed();
            super.succeeded();
            if (_commit)
                notifyResponseCommit(_request);
            if (_length > 0)
                notifyResponseContent(_request, _content);
            if (_complete && _state.completeResponse())
                notifyResponseEnd(_request);
        }

        @Override
        public void failed(final Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Commit failed", x);

            if (x instanceof BadMessageException)
            {
                _transport.send(HttpGenerator.RESPONSE_500_INFO, false, null, true, new Callback.Nested(this)
                {
                    @Override
                    public void succeeded()
                    {
                        _response.getHttpOutput().closed();
                        super.failed(x);
                    }

                    @Override
                    public void failed(Throwable th)
                    {
                        _response.getHttpOutput().closed();
                        abort(x);
                        super.failed(x);
                    }
                });
            }
            else
            {
                abort(x);
                super.failed(x);
            }
        }
    }

    private class Send100Callback extends SendCallback
    {
        private Send100Callback(Callback callback)
        {
            super(callback, null, false, false);
        }

        @Override
        public void succeeded()
        {
            if (_state.partialResponse())
                super.succeeded();
            else
                super.failed(new IllegalStateException());
        }
    }
}
