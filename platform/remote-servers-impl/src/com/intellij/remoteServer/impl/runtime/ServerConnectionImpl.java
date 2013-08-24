package com.intellij.remoteServer.impl.runtime;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.ComponentContainer;
import com.intellij.remoteServer.configuration.RemoteServer;
import com.intellij.remoteServer.configuration.deployment.DeploymentConfiguration;
import com.intellij.remoteServer.configuration.deployment.DeploymentSource;
import com.intellij.remoteServer.impl.runtime.deployment.DeploymentImpl;
import com.intellij.remoteServer.impl.runtime.deployment.DeploymentTaskImpl;
import com.intellij.remoteServer.impl.runtime.log.LoggingHandlerImpl;
import com.intellij.remoteServer.runtime.ConnectionStatus;
import com.intellij.remoteServer.runtime.Deployment;
import com.intellij.remoteServer.runtime.ServerConnection;
import com.intellij.remoteServer.runtime.ServerConnector;
import com.intellij.remoteServer.runtime.deployment.DeploymentRuntime;
import com.intellij.remoteServer.runtime.deployment.DeploymentStatus;
import com.intellij.remoteServer.runtime.deployment.DeploymentTask;
import com.intellij.remoteServer.runtime.deployment.ServerRuntimeInstance;
import com.intellij.remoteServer.runtime.deployment.debug.DebugConnectionData;
import com.intellij.remoteServer.runtime.deployment.debug.DebugConnectionDataNotAvailableException;
import com.intellij.remoteServer.runtime.deployment.debug.DebugConnector;
import com.intellij.util.ParameterizedRunnable;
import com.intellij.util.containers.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author nik
 */
public class ServerConnectionImpl<D extends DeploymentConfiguration> implements ServerConnection<D> {
  private static final Logger LOG = Logger.getInstance(ServerConnectionImpl.class);
  private final RemoteServer<?> myServer;
  private final ServerConnector<D> myConnector;
  private final ServerConnectionEventDispatcher myEventDispatcher;
  private final ServerConnectionManagerImpl myConnectionManager;
  private volatile ConnectionStatus myStatus = ConnectionStatus.DISCONNECTED;
  private volatile String myStatusText;
  private volatile ServerRuntimeInstance<D> myRuntimeInstance;
  private final Map<String, Deployment> myRemoteDeployments = new HashMap<String, Deployment>();
  private final Map<String, Deployment> myLocalDeployments = Collections.synchronizedMap(new HashMap<String, Deployment>());
  private final Map<String, LoggingHandlerImpl> myLoggingHandlers = new ConcurrentHashMap<String, LoggingHandlerImpl>();

  public ServerConnectionImpl(RemoteServer<?> server, ServerConnector connector, ServerConnectionManagerImpl connectionManager) {
    myServer = server;
    myConnector = connector;
    myConnectionManager = connectionManager;
    myEventDispatcher = myConnectionManager.getEventDispatcher();
  }

  @NotNull
  @Override
  public RemoteServer<?> getServer() {
    return myServer;
  }

  @NotNull
  @Override
  public ConnectionStatus getStatus() {
    return myStatus;
  }

  @NotNull
  @Override
  public String getStatusText() {
    return myStatusText != null ? myStatusText : myStatus.getPresentableText();
  }

  @Override
  public void connect(@NotNull final Runnable onFinished) {
    doDisconnect();
    connectIfNeeded(new ServerConnector.ConnectionCallback<D>() {
      @Override
      public void connected(@NotNull ServerRuntimeInstance<D> serverRuntimeInstance) {
        onFinished.run();
      }

      @Override
      public void errorOccurred(@NotNull String errorMessage) {
        onFinished.run();
      }
    });
  }

  @Override
  public void disconnect() {
    myConnectionManager.removeConnection(myServer);
    doDisconnect();
  }

  private void doDisconnect() {
    if (myStatus == ConnectionStatus.CONNECTED) {
      if (myRuntimeInstance != null) {
        myRuntimeInstance.disconnect();
        myRuntimeInstance = null;
      }
      setStatus(ConnectionStatus.DISCONNECTED);
    }
  }

  @Override
  public void deploy(@NotNull final DeploymentTask<D> task, @NotNull final ParameterizedRunnable<String> onDeploymentStarted) {
    connectIfNeeded(new ConnectionCallbackBase<D>() {
      @Override
      public void connected(@NotNull ServerRuntimeInstance<D> instance) {
        DeploymentSource source = task.getSource();
        String deploymentName = instance.getDeploymentName(source);
        myLocalDeployments.put(deploymentName, new DeploymentImpl(deploymentName, DeploymentStatus.DEPLOYING, null, null));
        LoggingHandlerImpl handler = (LoggingHandlerImpl)task.getLoggingHandler();
        handler.printlnSystemMessage("Deploying '" + deploymentName + "'...");
        myLoggingHandlers.put(deploymentName, handler);
        onDeploymentStarted.run(deploymentName);
        instance.deploy(task, new DeploymentOperationCallbackImpl(deploymentName, (DeploymentTaskImpl<D>)task, handler));
      }
    });
  }

  @Override
  @Nullable
  public ComponentContainer getLogConsole(@NotNull Deployment deployment) {
    LoggingHandlerImpl handler = myLoggingHandlers.get(deployment.getName());
    return handler != null ? handler.getConsole() : null;
  }

  @Override
  public void computeDeployments(@NotNull final Runnable onFinished) {
    connectIfNeeded(new ConnectionCallbackBase<D>() {
      @Override
      public void connected(@NotNull ServerRuntimeInstance<D> instance) {
        instance.computeDeployments(new ServerRuntimeInstance.ComputeDeploymentsCallback() {
          private final List<Deployment> myDeployments = new ArrayList<Deployment>();

          @Override
          public void addDeployment(@NotNull String deploymentName) {
            myDeployments.add(new DeploymentImpl(deploymentName, DeploymentStatus.DEPLOYED, null, null));
          }

          @Override
          public void succeeded() {
            synchronized (myRemoteDeployments) {
              myRemoteDeployments.clear();
              for (Deployment deployment : myDeployments) {
                myRemoteDeployments.put(deployment.getName(), deployment);
              }
            }
            myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
            onFinished.run();
          }

          @Override
          public void errorOccurred(@NotNull String errorMessage) {
            synchronized (myRemoteDeployments) {
              myRemoteDeployments.clear();
            }
            myStatusText = "Cannot obtain deployments: " + errorMessage;
            myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
            onFinished.run();
          }
        });
      }
    });
  }

  @Override
  public void undeploy(@NotNull Deployment deployment, @NotNull final DeploymentRuntime runtime) {
    final String deploymentName = deployment.getName();
    myLocalDeployments.put(deploymentName, new DeploymentImpl(deploymentName, DeploymentStatus.UNDEPLOYING, null, null));
    myEventDispatcher.queueDeploymentsChanged(this);
    final LoggingHandlerImpl loggingHandler = myLoggingHandlers.get(deploymentName);
    loggingHandler.printlnSystemMessage("Undeploying '" + deploymentName + "'...");
    runtime.undeploy(new DeploymentRuntime.UndeploymentTaskCallback() {
      @Override
      public void succeeded() {
        loggingHandler.printlnSystemMessage("'" + deploymentName + "' has been undeployed successfully.");
        myLocalDeployments.remove(deploymentName);
        myLoggingHandlers.remove(deploymentName);
        myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
      }

      @Override
      public void errorOccurred(@NotNull String errorMessage) {
        loggingHandler.printlnSystemMessage("Failed to undeploy '" + deploymentName + "': " + errorMessage);
        myLocalDeployments.put(deploymentName, new DeploymentImpl(deploymentName, DeploymentStatus.DEPLOYED, errorMessage, runtime));
        myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
      }
    });
  }

  @NotNull
  @Override
  public Collection<Deployment> getDeployments() {
    Map<String, Deployment> result;
    synchronized (myRemoteDeployments) {
      result = new HashMap<String, Deployment>(myRemoteDeployments);
    }
    synchronized (myLocalDeployments) {
      for (Deployment deployment : myLocalDeployments.values()) {
        result.put(deployment.getName(), deployment);
      }
    }
    return result.values();
  }

  private void connectIfNeeded(final ServerConnector.ConnectionCallback<D> callback) {
    final ServerRuntimeInstance<D> instance = myRuntimeInstance;
    if (instance != null) {
      callback.connected(instance);
      return;
    }

    setStatus(ConnectionStatus.CONNECTING);
    myConnector.connect(new ServerConnector.ConnectionCallback<D>() {
      @Override
      public void connected(@NotNull ServerRuntimeInstance<D> instance) {
        setStatus(ConnectionStatus.CONNECTED);
        myRuntimeInstance = instance;
        callback.connected(instance);
      }

      @Override
      public void errorOccurred(@NotNull String errorMessage) {
        setStatus(ConnectionStatus.DISCONNECTED);
        myRuntimeInstance = null;
        myStatusText = errorMessage;
        callback.errorOccurred(errorMessage);
      }
    });
  }

  private void setStatus(final ConnectionStatus status) {
    myStatus = status;
    myEventDispatcher.queueConnectionStatusChanged(this);
  }

  private static abstract class ConnectionCallbackBase<D extends DeploymentConfiguration> implements ServerConnector.ConnectionCallback<D> {
    @Override
    public void errorOccurred(@NotNull String errorMessage) {
    }
  }

  private class DeploymentOperationCallbackImpl implements ServerRuntimeInstance.DeploymentOperationCallback {
    private final String myDeploymentName;
    private final DeploymentTaskImpl<D> myDeploymentTask;
    private final LoggingHandlerImpl myLoggingHandler;

    public DeploymentOperationCallbackImpl(String deploymentName, DeploymentTaskImpl<D> deploymentTask, LoggingHandlerImpl handler) {
      myDeploymentName = deploymentName;
      myDeploymentTask = deploymentTask;
      myLoggingHandler = handler;
    }

    @Override
    public void succeeded(@NotNull DeploymentRuntime deployment) {
      myLoggingHandler.printlnSystemMessage("'" + myDeploymentName + "' has been deployed successfully.");
      myLocalDeployments.put(myDeploymentName, new DeploymentImpl(myDeploymentName, DeploymentStatus.DEPLOYED, null, deployment));
      myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
      DebugConnector<?,?> debugConnector = myDeploymentTask.getDebugConnector();
      if (debugConnector != null) {
        launchDebugger(debugConnector, deployment);
      }
    }

    private <D extends DebugConnectionData, R extends DeploymentRuntime> void launchDebugger(@NotNull final DebugConnector<D, R> debugConnector,
                                                                                             @NotNull DeploymentRuntime runtime) {
      try {
        final D debugInfo = debugConnector.getConnectionData((R)runtime);
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            try {
              debugConnector.getLauncher().startDebugSession(debugInfo, myDeploymentTask.getExecutionEnvironment(), myServer);
            }
            catch (ExecutionException e) {
              myLoggingHandler.print("Cannot start debugger: " + e.getMessage() + "\n");
              LOG.info(e);
            }
          }
        });
      }
      catch (DebugConnectionDataNotAvailableException e) {
        myLoggingHandler.print("Cannot retrieve debug connection: " + e.getMessage() + "\n");
        LOG.info(e);
      }
    }

    @Override
    public void errorOccurred(@NotNull String errorMessage) {
      myLoggingHandler.printlnSystemMessage("Failed to deploy '" + myDeploymentName + "': " + errorMessage);
      myLocalDeployments.put(myDeploymentName, new DeploymentImpl(myDeploymentName, DeploymentStatus.NOT_DEPLOYED, errorMessage, null));
      myEventDispatcher.queueDeploymentsChanged(ServerConnectionImpl.this);
    }
  }
}
