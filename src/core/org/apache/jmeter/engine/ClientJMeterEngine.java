/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.engine;

import java.io.File;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.Properties;

import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.SearchByClass;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 * Class to run remote tests from the client JMeter and collect remote samples
 */
public class ClientJMeterEngine implements JMeterEngine {
    private static final Logger log = LoggingManager.getLoggerForClass();

    private static final Object LOCK = new Object();

    private RemoteJMeterEngine remote;

    private HashTree test;

    private final String host;

    private static RemoteJMeterEngine getEngine(String h) throws MalformedURLException, RemoteException,
            NotBoundException {
       final String name = "//" + h + "/" + RemoteJMeterEngineImpl.JMETER_ENGINE_RMI_NAME; // $NON-NLS-1$ $NON-NLS-2$
       Remote remobj = Naming.lookup(name);
       if (remobj instanceof RemoteJMeterEngine){
           final RemoteJMeterEngine rje = (RemoteJMeterEngine) remobj;
           if (remobj instanceof RemoteObject){
               RemoteObject robj = (RemoteObject) remobj;
               System.out.println("Using remote object: "+robj.getRef().remoteToString());
           }
           return rje;
       }
       throw new RemoteException("Could not find "+name);
    }

    public ClientJMeterEngine(String host) throws MalformedURLException, NotBoundException, RemoteException {
        this.remote = getEngine(host);
        this.host = host;
    }

    /** {@inheritDoc} */
    public void configure(HashTree testTree) {
        TreeCloner cloner = new TreeCloner(false);
        testTree.traverse(cloner);
        test = cloner.getClonedTree();
    }

    /** {@inheritDoc} */
    public void stopTest(boolean now) {
        log.info("about to "+(now ? "stop" : "shutdown")+" remote test on "+host);
        try {
            remote.stopTest(now);
        } catch (Exception ex) {
            log.error("", ex); // $NON-NLS-1$
        }
    }

    /** {@inheritDoc} */
    public void reset() {
        try {
            try {
                remote.reset();
            } catch (java.rmi.ConnectException e) {
                log.info("Retry reset after: "+e);
                remote = getEngine(host);
                remote.reset();
            }
        } catch (Exception ex) {
            log.error("Failed to reset remote engine", ex); // $NON-NLS-1$
        }
    }

    public void runTest() throws JMeterEngineException {
        log.info("running clientengine run method");
        SearchByClass<TestListener> testListeners = new SearchByClass<TestListener>(TestListener.class);
        ConvertListeners sampleListeners = new ConvertListeners();
        HashTree testTree = test;
        PreCompiler compiler = new PreCompiler(true); // limit the changes to client only test elements
        synchronized(testTree) {
            testTree.traverse(compiler);
            testTree.traverse(new TurnElementsOn());
            testTree.traverse(testListeners);
            testTree.traverse(sampleListeners);
        }

        try {
            JMeterContextService.startTest();
            /*
             * Add fix for Deadlocks, see:
             * 
             * See https://issues.apache.org/bugzilla/show_bug.cgi?id=48350
            */
            File baseDirRelative = FileServer.getFileServer().getBaseDirRelative();
            synchronized(LOCK)
            {
                remote.configure(testTree, host, baseDirRelative);
            }
            log.info("sent test to " + host + " basedir='"+baseDirRelative+"'"); // $NON-NLS-1$
            if (savep != null){
                log.info("Sending properties "+savep);
                try {
                    remote.setProperties(savep);
                } catch (RemoteException e) {
                    log.warn("Could not set properties: " + e.toString());
                }
            }
            remote.runTest();
            log.info("sent run command to "+ host);
        } catch (IllegalStateException ex) {
            log.error("Error in run() method "+ex); // $NON-NLS-1$
            tidyRMI(log);
            throw ex; // Don't wrap this error - display it as is
        } catch (Exception ex) {
            log.error("Error in run() method "+ex); // $NON-NLS-1$
            tidyRMI(log);
            throw new JMeterEngineException("Error in run() method "+ex, ex); // $NON-NLS-1$
        }
    }

    /**
     * Tidy up RMI access to allow JMeter client to exit.
     * Currently just interrups the "RMI Reaper" thread.
     * @param logger where to log the information
     */
    public static void tidyRMI(Logger logger) {
        for(Thread t : Thread.getAllStackTraces().keySet()){
            String reaperRE = JMeterUtils.getPropDefault("rmi.thread.name", "^RMI Reaper$");
            String name = t.getName();
            if (name.matches(reaperRE)) {
                logger.info("Interrupting "+name);
                t.interrupt();
            }
        }
    }

    /** {@inheritDoc} */
    public void exit() {
        log.info("about to exit remote server on "+host);
        try {
            remote.exit();
        } catch (RemoteException e) {
            log.warn("Could not perform remote exit: " + e.toString());
        }
    }

    private Properties savep;
    /** {@inheritDoc} */
    public void setProperties(Properties p) {
        savep = p;
        // Sent later
    }

    public boolean isActive() {
        return true;
    }
}
