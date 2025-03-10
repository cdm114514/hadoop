/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.protocolPB;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.SocketFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.ReconfigurationTaskStatus;
import org.apache.hadoop.conf.ReconfigurationUtil.PropertyChange;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.hdfs.client.BlockReportOptions;
import org.apache.hadoop.hdfs.protocol.BlockLocalPathInfo;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeLocalInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsBlocksMetadata;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.DeleteBlockPoolRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetBlockLocalPathInfoRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetBlockLocalPathInfoResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetDatanodeInfoRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetDatanodeInfoResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetHdfsBlockLocationsRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetHdfsBlockLocationsResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReplicaVisibleLengthRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.RefreshNamenodesRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReconfigurationStatusRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReconfigurationStatusResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReconfigurationStatusConfigChangeProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.ShutdownDatanodeRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.StartReconfigurationRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.TriggerBlockReportRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.TriggerBlockReportResponseProto;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.ipc.ProtobufHelper;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.ProtocolMetaInterface;
import org.apache.hadoop.ipc.ProtocolTranslator;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RpcClientUtil;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.proto.SecurityProtos.TokenProto;
import org.apache.hadoop.security.token.Token;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
 * This class is the client side translator to translate the requests made on
 * {@link ClientDatanodeProtocol} interfaces to the RPC server implementing
 * {@link ClientDatanodeProtocolPB}.
 */
@InterfaceAudience.Private
@InterfaceStability.Stable
public class ClientDatanodeProtocolTranslatorPB implements ProtocolMetaInterface, ClientDatanodeProtocol, ProtocolTranslator, Closeable
{
    public static final Log LOG = LogFactory.getLog(ClientDatanodeProtocolTranslatorPB.class);

    /**
     * RpcController is not used and hence is set to null
     */
    private final static RpcController NULL_CONTROLLER = null;
    private final ClientDatanodeProtocolPB rpcProxy;
    private final static RefreshNamenodesRequestProto VOID_REFRESH_NAMENODES = RefreshNamenodesRequestProto.newBuilder().build();
    private final static GetDatanodeInfoRequestProto VOID_GET_DATANODE_INFO = GetDatanodeInfoRequestProto.newBuilder().build();
    private final static GetReconfigurationStatusRequestProto VOID_GET_RECONFIG_STATUS = GetReconfigurationStatusRequestProto.newBuilder().build();
    private final static StartReconfigurationRequestProto VOID_START_RECONFIG = StartReconfigurationRequestProto.newBuilder().build();

    public ClientDatanodeProtocolTranslatorPB(DatanodeID datanodeid, Configuration conf, int socketTimeout, boolean connectToDnViaHostname, LocatedBlock locatedBlock) throws IOException
    {
        rpcProxy = createClientDatanodeProtocolProxy(datanodeid, conf, socketTimeout, connectToDnViaHostname, locatedBlock);
    }

    public ClientDatanodeProtocolTranslatorPB(InetSocketAddress addr, UserGroupInformation ticket, Configuration conf, SocketFactory factory) throws IOException
    {
        rpcProxy = createClientDatanodeProtocolProxy(addr, ticket, conf, factory, 0);
    }

    /**
     * Constructor.
     *
     * @param datanodeid             Datanode to connect to.
     * @param conf                   Configuration.
     * @param socketTimeout          Socket timeout to use.
     * @param connectToDnViaHostname connect to the Datanode using its hostname
     * @throws IOException
     */
    public ClientDatanodeProtocolTranslatorPB(DatanodeID datanodeid, Configuration conf, int socketTimeout, boolean connectToDnViaHostname) throws IOException
    {
        final String dnAddr = datanodeid.getIpcAddr(connectToDnViaHostname);
        InetSocketAddress addr = NetUtils.createSocketAddr(dnAddr);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Connecting to datanode " + dnAddr + " addr=" + addr);
        }
        rpcProxy = createClientDatanodeProtocolProxy(addr, UserGroupInformation.getCurrentUser(), conf, NetUtils.getDefaultSocketFactory(conf), socketTimeout);
    }

    static ClientDatanodeProtocolPB createClientDatanodeProtocolProxy(DatanodeID datanodeid, Configuration conf, int socketTimeout, boolean connectToDnViaHostname, LocatedBlock locatedBlock) throws IOException
    {
        final String dnAddr = datanodeid.getIpcAddr(connectToDnViaHostname);
        InetSocketAddress addr = NetUtils.createSocketAddr(dnAddr);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Connecting to datanode " + dnAddr + " addr=" + addr);
        }

        // Since we're creating a new UserGroupInformation here, we know that no
        // future RPC proxies will be able to re-use the same connection. And
        // usages of this proxy tend to be one-off calls.
        //
        // This is a temporary fix: callers should really achieve this by using
        // RPC.stopProxy() on the resulting object, but this is currently not
        // working in trunk. See the discussion on HDFS-1965.
        Configuration confWithNoIpcIdle = new Configuration(conf);
        confWithNoIpcIdle.setInt(CommonConfigurationKeysPublic.IPC_CLIENT_CONNECTION_MAXIDLETIME_KEY, 0);

        UserGroupInformation ticket = UserGroupInformation.createRemoteUser(locatedBlock.getBlock().getLocalBlock().toString());
        ticket.addToken(locatedBlock.getBlockToken());
        return createClientDatanodeProtocolProxy(addr, ticket, confWithNoIpcIdle, NetUtils.getDefaultSocketFactory(conf), socketTimeout);
    }

    static ClientDatanodeProtocolPB createClientDatanodeProtocolProxy(InetSocketAddress addr, UserGroupInformation ticket, Configuration conf, SocketFactory factory, int socketTimeout) throws IOException
    {
        RPC.setProtocolEngine(conf, ClientDatanodeProtocolPB.class, ProtobufRpcEngine.class);
        return RPC.getProxy(ClientDatanodeProtocolPB.class, RPC.getProtocolVersion(ClientDatanodeProtocolPB.class), addr, ticket, conf, factory, socketTimeout);
    }

    @Override
    public void close()
    {
        RPC.stopProxy(rpcProxy);
    }

    @Override
    public long getReplicaVisibleLength(ExtendedBlock b) throws IOException
    {
        GetReplicaVisibleLengthRequestProto req = GetReplicaVisibleLengthRequestProto.newBuilder().setBlock(PBHelper.convert(b)).build();
        try
        {
            return rpcProxy.getReplicaVisibleLength(NULL_CONTROLLER, req).getLength();
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public void refreshNamenodes() throws IOException
    {
        try
        {
            rpcProxy.refreshNamenodes(NULL_CONTROLLER, VOID_REFRESH_NAMENODES);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public void deleteBlockPool(String bpid, boolean force) throws IOException
    {
        DeleteBlockPoolRequestProto req = DeleteBlockPoolRequestProto.newBuilder().setBlockPool(bpid).setForce(force).build();
        try
        {
            rpcProxy.deleteBlockPool(NULL_CONTROLLER, req);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public BlockLocalPathInfo getBlockLocalPathInfo(ExtendedBlock block, Token<BlockTokenIdentifier> token) throws IOException
    {
        GetBlockLocalPathInfoRequestProto req = GetBlockLocalPathInfoRequestProto.newBuilder().setBlock(PBHelper.convert(block)).setToken(PBHelper.convert(token)).build();
        GetBlockLocalPathInfoResponseProto resp;
        try
        {
            resp = rpcProxy.getBlockLocalPathInfo(NULL_CONTROLLER, req);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
        return new BlockLocalPathInfo(PBHelper.convert(resp.getBlock()), resp.getLocalPath(), resp.getLocalMetaPath());
    }

    @Override
    public boolean isMethodSupported(String methodName) throws IOException
    {
        return RpcClientUtil.isMethodSupported(rpcProxy, ClientDatanodeProtocolPB.class, RPC.RpcKind.RPC_PROTOCOL_BUFFER, RPC.getProtocolVersion(ClientDatanodeProtocolPB.class), methodName);
    }

    @Override
    public Object getUnderlyingProxyObject()
    {
        return rpcProxy;
    }

    @Override
    public HdfsBlocksMetadata getHdfsBlocksMetadata(String blockPoolId, long[] blockIds, List<Token<BlockTokenIdentifier>> tokens) throws IOException
    {
        List<TokenProto> tokensProtos = new ArrayList<TokenProto>(tokens.size());
        for (Token<BlockTokenIdentifier> t : tokens)
        {
            tokensProtos.add(PBHelper.convert(t));
        }
        // Build the request
        GetHdfsBlockLocationsRequestProto request = GetHdfsBlockLocationsRequestProto.newBuilder().setBlockPoolId(blockPoolId).addAllBlockIds(Longs.asList(blockIds)).addAllTokens(tokensProtos).build();
        // Send the RPC
        GetHdfsBlockLocationsResponseProto response;
        try
        {
            response = rpcProxy.getHdfsBlockLocations(NULL_CONTROLLER, request);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
        // List of volumes in the response
        List<ByteString> volumeIdsByteStrings = response.getVolumeIdsList();
        List<byte[]> volumeIds = new ArrayList<byte[]>(volumeIdsByteStrings.size());
        for (ByteString bs : volumeIdsByteStrings)
        {
            volumeIds.add(bs.toByteArray());
        }
        // Array of indexes into the list of volumes, one per block
        List<Integer> volumeIndexes = response.getVolumeIndexesList();
        // Parsed HdfsVolumeId values, one per block
        return new HdfsBlocksMetadata(blockPoolId, blockIds, volumeIds, volumeIndexes);
    }

    @Override
    public void shutdownDatanode(boolean forUpgrade) throws IOException
    {
        ShutdownDatanodeRequestProto request = ShutdownDatanodeRequestProto.newBuilder().setForUpgrade(forUpgrade).build();
        try
        {
            rpcProxy.shutdownDatanode(NULL_CONTROLLER, request);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public DatanodeLocalInfo getDatanodeInfo() throws IOException
    {
        GetDatanodeInfoResponseProto response;
        try
        {
            response = rpcProxy.getDatanodeInfo(NULL_CONTROLLER, VOID_GET_DATANODE_INFO);
            return PBHelper.convert(response.getLocalInfo());
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public void startReconfiguration() throws IOException
    {
        try
        {
            rpcProxy.startReconfiguration(NULL_CONTROLLER, VOID_START_RECONFIG);
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }

    @Override
    public ReconfigurationTaskStatus getReconfigurationStatus() throws IOException
    {
        GetReconfigurationStatusResponseProto response;
        Map<PropertyChange, Optional<String>> statusMap = null;
        long startTime;
        long endTime = 0;
        try
        {
            response = rpcProxy.getReconfigurationStatus(NULL_CONTROLLER, VOID_GET_RECONFIG_STATUS);
            startTime = response.getStartTime();
            if (response.hasEndTime())
            {
                endTime = response.getEndTime();
            }
            if (response.getChangesCount() > 0)
            {
                statusMap = Maps.newHashMap();
                for (GetReconfigurationStatusConfigChangeProto change : response.getChangesList())
                {
                    PropertyChange pc = new PropertyChange(change.getName(), change.getNewValue(), change.getOldValue());
                    String errorMessage = null;
                    if (change.hasErrorMessage())
                    {
                        errorMessage = change.getErrorMessage();
                    }
                    statusMap.put(pc, Optional.fromNullable(errorMessage));
                }
            }
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
        return new ReconfigurationTaskStatus(startTime, endTime, statusMap);
    }

    @Override
    public void triggerBlockReport(BlockReportOptions options) throws IOException
    {
        try
        {
            rpcProxy.triggerBlockReport(NULL_CONTROLLER, TriggerBlockReportRequestProto.newBuilder().setIncremental(options.isIncremental()).build());
        } catch (ServiceException e)
        {
            throw ProtobufHelper.getRemoteException(e);
        }
    }
}
