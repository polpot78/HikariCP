/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.hikari.proxy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zaxxer.hikari.HikariPool;
import com.zaxxer.hikari.javassist.HikariInject;

/**
 * This is the proxy class for java.sql.Connection.  It is used in
 * two ways:
 * 
 *  1) If instrumentation is not used, Javassist will generate a new class
 *     that extends this class and delegates all method calls to the 'delegate'
 *     member (which points to the real Connection).
 *
 *  2) If instrumentation IS used, Javassist will be used to inject all of
 *     the non-final methods of this class into the actual Connection implementation
 *     provided by the JDBC driver.  All of the fields, <i>except</i> for PROXY_FACTORY
 *     and 'delegate' are also injected.  In order to avoid name conflicts the
 *     fields of this class have slightly unconventional names.
 *
 * @author Brett Wooldridge
 */
public class ConnectionProxy extends HikariProxyBase implements IHikariConnectionProxy
{
    private static ProxyFactory PROXY_FACTORY;

    @HikariInject private static final Set<String> POSTGRESQL_ERRORS;
    @HikariInject private static final Set<String> SPECIAL_ERRORS;

    @HikariInject private ArrayList<Statement> _openStatements;
    @HikariInject private AtomicBoolean _isClosed;
    @HikariInject private HikariPool _parentPool;
    
    protected final Connection delegate;

    @HikariInject private volatile boolean _forceClose;
    @HikariInject private long _creationTime;
    @HikariInject private long _lastAccess;

    @HikariInject private StackTraceElement[] _stackTrace;
    @HikariInject private TimerTask _leakTask;

    // static initializer
    static
    {
        POSTGRESQL_ERRORS = new HashSet<String>();
        POSTGRESQL_ERRORS.add("57P01");  // ADMIN SHUTDOWN
        POSTGRESQL_ERRORS.add("57P02");  // CRASH SHUTDOWN
        POSTGRESQL_ERRORS.add("57P03");  // CANNOT CONNECT NOW
        POSTGRESQL_ERRORS.add("57P02");  // CRASH SHUTDOWN

        SPECIAL_ERRORS = new HashSet<String>();
        SPECIAL_ERRORS.add("01002");  // SQL92 disconnect error

        __static();
    }

    protected ConnectionProxy(HikariPool parentPool, Connection connection)
    {
        this._parentPool = parentPool;
        this.delegate = connection;

        __init();
    }

    @HikariInject 
    public void unregisterStatement(Object statement)
    {
        // If the connection is not closed.  If it is closed, it means this is being
        // called back as a result of the close() method below in which case we
        // will clear the openStatements collection en mass.
        if (!_isClosed.get())
        {
            _openStatements.remove(statement);
        }
    }

    @HikariInject 
    public long getCreationTime()
    {
        return _creationTime;
    }

    @HikariInject 
    public long getLastAccess()
    {
        return _lastAccess;
    }

    @HikariInject 
    public void setLastAccess(long timestamp)
    {
        this._lastAccess = timestamp;
    }

    @HikariInject
    public void setParentPool(HikariPool parentPool)
    {
        this._parentPool = parentPool;
    }

    @HikariInject 
    public void unclose()
    {
        _isClosed.set(false);
    }

    @HikariInject 
    public void captureStack(long leakDetectionThreshold, Timer scheduler)
    {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        _stackTrace = new StackTraceElement[trace.length - 4];
        System.arraycopy(trace, 4, _stackTrace, 0, _stackTrace.length);

        _leakTask = new LeakTask(_stackTrace, leakDetectionThreshold);
        scheduler.schedule(_leakTask, leakDetectionThreshold);
    }

    @HikariInject 
    public boolean isBrokenConnection()
    {
        return _forceClose;
    }

    @HikariInject 
    public SQLException checkException(SQLException sqle)
    {
        String sqlState = sqle.getSQLState();
        if (sqlState == null)
        {
            return sqle;
        }

        sqlState = sqlState.toUpperCase();
        if (sqlState.startsWith("08"))
        {
            _forceClose = true;
        }
        else if (POSTGRESQL_ERRORS.contains(sqlState.toUpperCase()) || SPECIAL_ERRORS.contains(sqlState))
        {
            _forceClose = true;
        }

        return sqle;
    }

    @HikariInject
    private void __init()
    {
        _openStatements = new ArrayList<Statement>(64);
        _isClosed = new AtomicBoolean();
        _creationTime = _lastAccess = System.currentTimeMillis();
    }

    public final Connection getDelegate()
    {
        return delegate;
    }

    // **********************************************************************
    //                   "Overridden" java.sql.Connection Methods
    // **********************************************************************

    @HikariInject
    public void close() throws SQLException
    {
        if (_isClosed.compareAndSet(false, true))
        {
            if (_leakTask != null)
            {
                _leakTask.cancel();
                _leakTask = null;
            }

            try
            {
                
                // Faster than an iterator
                for (int i = _openStatements.size() - 1; i >= 0; i--)
                {
                    _openStatements.get(i).close();
                }
            }
            catch (SQLException e)
            {
                throw checkException(e);
            }
            finally
            {
                _openStatements.clear();
                _parentPool.releaseConnection(this);
            }
        }
    }

    @HikariInject
    public boolean isClosed() throws SQLException
    {
        return _isClosed.get();
    }

    @HikariInject
    public Statement createStatement() throws SQLException
    {
        try
        {
            Statement statementProxy = __createStatement();
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException
    {
        try
        {
            Statement statementProxy = __createStatement(resultSetType, resultSetConcurrency);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        try
        {
            Statement statementProxy = __createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public CallableStatement prepareCall(String sql) throws SQLException
    {
        try
        {
            CallableStatement statementProxy = __prepareCall(sql);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException
    {
        try
        {
            CallableStatement statementProxy = __prepareCall(sql, resultSetType, resultSetConcurrency);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        try
        {
            CallableStatement statementProxy = __prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql, autoGeneratedKeys);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql, resultSetType, resultSetConcurrency);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql, columnIndexes);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    @HikariInject
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException
    {
        try
        {
            PreparedStatement statementProxy = __prepareStatement(sql, columnNames);
            ((IHikariStatementProxy) statementProxy).setConnectionProxy(this);
            _openStatements.add(statementProxy);

            return statementProxy;
        }
        catch (SQLException e)
        {
            throw checkException(e);
        }
    }

    public boolean getAutoCommit() throws SQLException
    {
        return delegate.getAutoCommit();
    }

    public void commit() throws SQLException
    {
        delegate.commit();
    }

    // ***********************************************************************
    // These methods contain code we do not want injected into the actual
    // java.sql.Connection implementation class.  These methods are only
    // used when instrumentation is not available and "conventional" Javassist
    // delegating proxies are used.
    // ***********************************************************************

    private static void __static()
    {
        if (PROXY_FACTORY == null)
        {
            PROXY_FACTORY = JavassistProxyFactoryFactory.getProxyFactory();
        }
    }

    public final void __close() throws SQLException
    {
        delegate.close();
    }

    public final Statement __createStatement() throws SQLException
    {
        return PROXY_FACTORY.getProxyStatement(this, delegate.createStatement());
    }

    public final Statement __createStatement(int resultSetType, int resultSetConcurrency) throws SQLException
    {
        return PROXY_FACTORY.getProxyStatement(this, delegate.createStatement(resultSetType, resultSetConcurrency));
    }

    public final Statement __createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        return PROXY_FACTORY.getProxyStatement(this, delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    public final CallableStatement __prepareCall(String sql) throws SQLException
    {
        return PROXY_FACTORY.getProxyCallableStatement(this, delegate.prepareCall(sql));
    }

    public final CallableStatement __prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException
    {
        return PROXY_FACTORY.getProxyCallableStatement(this, delegate.prepareCall(sql, resultSetType, resultSetConcurrency));
    }

    public final CallableStatement __prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        return PROXY_FACTORY.getProxyCallableStatement(this, delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    public final PreparedStatement __prepareStatement(String sql) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql));
    }

    public final PreparedStatement __prepareStatement(String sql, int autoGeneratedKeys) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql, autoGeneratedKeys));
    }

    public final PreparedStatement __prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql, resultSetType, resultSetConcurrency));
    }

    public final PreparedStatement __prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    public final PreparedStatement __prepareStatement(String sql, int[] columnIndexes) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql, columnIndexes));
    }

    public final PreparedStatement __prepareStatement(String sql, String[] columnNames) throws SQLException
    {
        return PROXY_FACTORY.getProxyPreparedStatement(this, delegate.prepareStatement(sql, columnNames));
    }
}