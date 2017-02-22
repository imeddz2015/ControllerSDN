package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;

public class LoadBalancer implements IOFMessageListener, IFloodlightModule, ILoadBalancerREST {
	
	//Services
	protected IFloodlightProviderService floodlightProvider; //Reference to the provider
	protected IRestApiService restApiService; //Reference to the Rest API service
	protected static IRoutingService routingEngineService; //Reference to the routing manager
	protected static IDeviceService deviceManagerService; //Reference to the device manager
	protected static IOFSwitchService switchService;	//Reference to the stich manager
	
	//Tables for registering addresses and counting requests
	public static ServiceRegister servReg;
	protected static ServiceCounter servCounter;
	
	//Timeouts of the rules
	public final static short IDLE_TIMEOUT = 10; // in seconds
	public final static short HARD_TIMEOUT = 20; // in seconds
	
	public static MacAddress[] anycastMacAddress = {MacAddress.of("00:00:00:00:00:01"),
												MacAddress.of("00:00:00:00:00:02")};
	@Override
	public String getName() {
		return LoadBalancer.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILoadBalancerREST.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(ILoadBalancerREST.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(IFloodlightProviderService.class);
	    l.add(IRestApiService.class);
	    return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		routingEngineService = context.getServiceImpl(IRoutingService.class);
		deviceManagerService = context.getServiceImpl(IDeviceService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		
		servReg = new ServiceRegister();
		servCounter = new ServiceCounter();						
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		restApiService.addRestletRoutable(new LoadBalancerWebRoutable());
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {
		
		OFPacketIn pi = (OFPacketIn) msg;
		
		Ethernet eth = IFloodlightProviderService.bcStore
				.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPacket inputPacket = eth.getPayload();
		
		if (eth.isBroadcast() || eth.isMulticast())
		{
			if (inputPacket instanceof ARP){
				ArpHandler.handle(eth, sw, pi, cntx);
			}
		}
		
		return Command.STOP;
	}
	
	@Override
	public String subscribe(IPv4Address anyAddr, ArrayList<ServerEntry> phyAddr) {
		int ret;
		synchronized (ServiceRegister.class) {
			ret = servReg.subscribe(anyAddr, phyAddr);
		}
		String message = ret + " service" + (ret!=1?"s":"") + " (out of " + phyAddr.size() + ") subscribed";
		System.out.println(message);
		return message;
	}
	
	@Override
	public String unsubscribe(IPv4Address anyAddr, ArrayList<ServerEntry> phyAddr) {
		int ret;
		synchronized (ServiceRegister.class) {
			ret = servReg.unsubscribe(anyAddr, phyAddr);
		}
		String message = ret + " service" + (ret!=1?"s":"") + " (out of " + phyAddr.size() + ") unsubscribed";
		System.out.println(message);
		return message;
	}

	@Override
	public ArrayList<IPv4Address> showList(IPv4Address anyAddr) {
		System.out.println(servReg);
		ArrayList<IPv4Address> phyAddr = servReg.getServersPhyAddr(anyAddr);
		return phyAddr;
	}
}