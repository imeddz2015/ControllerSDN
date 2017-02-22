package net.floodlightcontroller.loadbalancerproject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.VlanVid;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Path;

public class RouteHandler {
	/**
	 * The function returns the tuples <number of hops, Path, dest addr>,
	 * sorted by number of hops
	 * @param phys The list of physical IP addresses that are candidate
	 * destinations
	 * @param sourceDev The device that is the source of the message
	 * @param start The first switch that received the message from sourceDev
	 * @return The tuples <number of hops, Path, dest addr>, sorted by number
	 * of hops
	 */
	public static ArrayList<RouteInfo> findAllRoutes(ArrayList<IPv4Address> phys, IDevice sourceDev, DatapathId start) {
		if (phys==null || sourceDev==null)
			return null;
		
		ArrayList<RouteInfo> list = new ArrayList<>();
		for (IPv4Address dstIp : phys) {
			Iterator<? extends IDevice> IDev =
					LoadBalancer.deviceManagerService.queryDevices(
							MacAddress.NONE, VlanVid.ZERO, dstIp, IPv6Address.NONE,
							DatapathId.NONE, OFPort.ZERO
					);
			//if IDev==null you have to move to the next possible destination
			if (IDev==null || !IDev.hasNext())
				continue;
		
			SwitchPort[] dest = IDev.next().getAttachmentPoints();

			for (SwitchPort sswD : dest) {
				DatapathId end = sswD.getNodeId();
				Path route = LoadBalancer.routingEngineService.getPath(start, end);
				list.add(new RouteInfo(route.getHopCount(), dstIp, route));
			}
		}
		list.sort(new RouteInfo.RouteInfoComparator());
		return list;
	}
	
	/**
	 * Get the fastest route to get to the IP address specified by phy
	 * @param phy The destination IP address
	 * @param sourceDev The device that sent a packet to phy
	 * @param start The switch attached with sourceDev
	 * @return The fastest path to get to phy
	 */
	public static Path getFastestRoute(IPv4Address physicalAddr, IDevice sourceDev, DatapathId start) {
		ArrayList<IPv4Address> phys = new ArrayList<>();
		phys.add(physicalAddr);
		ArrayList<RouteInfo> routes = findAllRoutes(phys, sourceDev, start);
		Iterator<RouteInfo> entries = routes.iterator();
		if (entries.hasNext()) {
			Path route = entries.next().path;
			return route;
		}
		else return null;
	}
	
	/**
	 * Get the IP address of a non-busy server among the addresses in phys
	 * @param phys The IP addresses of the servers belonging to the same
	 * anycast group
	 * @param sourceDev The device that wants to reach the anycast group
	 * @param start The switch that attached with sourceDev
	 * @return The IP address of a non-busy server
	 */
	public static IPv4Address getNearestNotBusyServer(ArrayList<IPv4Address> phys, IDevice sourceDev, DatapathId start) {
		IPv4Address physicalAddr = null;
		ArrayList<RouteInfo> routes = findAllRoutes(phys, sourceDev, start);
		Iterator<RouteInfo> entries = routes.iterator();
		while (entries.hasNext()) {
			RouteInfo entry = entries.next();
			if (LoadBalancer.servCounter.isAvailable(entry.destination)) {
				physicalAddr = entry.destination;
				break;
			}	
		}
		return physicalAddr;
	}
	
	/**
	 * For all the switches in the route, write the FlowMod messages to
	 * directly forward packets
	 * @param dstIp The IP address of the destination
	 * @param route The route through the destination
	 * @param match The match associated with the incoming packets
	 * @param flowMod The FlowTable rule to ADD
	 */
	public static void pushRoute(IPv4Address dstIp, Path route, Match match, OFFlowAdd.Builder flowMod) {
		List<NodePortTuple> list = route.getPath();
		OFPort outPort = OFPort.NORMAL;
		IOFSwitch sw;
		Iterator<NodePortTuple> iterator = list.iterator();
		// The Path object has a structure like
		// || sw1, outport || sw2, inport || sw2, outport ||...
		// ||sw(n-1), inport || sw(n-1), outport || sw(n), inport ||
		// If the source sw is equal to the destination sw, the Path object is
		// empty
		while(iterator.hasNext()) {
			NodePortTuple npt = iterator.next();
			sw = LoadBalancer.switchService.getSwitch(npt.getNodeId());
			outPort = npt.getPortId();
			setRule(flowMod, match, outPort, sw);
			if (iterator.hasNext()) {
				// Skip the IN-port
				npt = iterator.next();
			}
		}
		// Handle the case of the last switch whose outport is not in the Path
		// object. The same procedure is applied when the source sw is equal to
		// the destination sw
		outPort = OFPort.NORMAL;
		sw = null;
		// Get the device whose address is dstIp
		Iterator<? extends IDevice> IDev =
				LoadBalancer.deviceManagerService.queryDevices(
						MacAddress.NONE, VlanVid.ZERO, dstIp, IPv6Address.NONE,
						DatapathId.NONE, OFPort.ZERO
				);
		if (IDev != null && IDev.hasNext()) {
			// Get the attachment points of  the device
			SwitchPort[] dest = IDev.next().getAttachmentPoints();
			for (int i = 0; i < dest.length; i++) {
				// Get the output port only if the output port belongs to
				// the considered switch (which is the destination switch)
				if (dest[0].getNodeId().equals(route.getId().getDst())) {
					outPort = dest[0].getPortId();
					sw = LoadBalancer.switchService.getSwitch(dest[0].getNodeId());
					break;
				}
			}
			if (sw == null) {
				return;
			}
			setRule(flowMod, match, outPort, sw);
		}
		return;
	}
	
	/**
	 * Write a new Flow Table modification entry
	 * @param flowMod The FlowTable rule to ADD
	 * @param match The match associated with the incoming packets
	 * @param outPort The port through which messages will be forwarded
	 * @param sw The switch where the FlowMod is installed
	 */
	private static void setRule(OFFlowAdd.Builder flowMod, Match match, OFPort outPort, IOFSwitch sw) {
		flowMod.setOutPort(outPort);
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFActionOutput setDstPort = sw.getOFFactory().actions().buildOutput()
			    .setMaxLen(0xFFffFFff)
			    .setPort(outPort)
			    .build();
		actionList.add(setDstPort);
		
		OFInstructionApplyActions applyActions = sw.getOFFactory().instructions().buildApplyActions()
			    .setActions(actionList)
			    .build();
		ArrayList<OFInstruction> instructionList = new ArrayList<OFInstruction>();
		instructionList.add(applyActions);
		
		// Apply the match and the actions associated with such match
		flowMod.setInstructions(instructionList)
				.setMatch(match);
		sw.write(flowMod.build());
	}
	
	/**
	 * Get the output port to use to forward the packet through the route
	 * @param dstIp The IP address of the destination
	 * @param route The computed route
	 * @param sw The current switch
	 * @return The output port
	 */
	public static OFPort getOutPort(IPv4Address dstIp, Path route, DatapathId sw) {
		OFPort outPort = OFPort.NORMAL;
		List<NodePortTuple> list = route.getPath();
		Iterator<NodePortTuple> iterator = list.iterator();
		if(!iterator.hasNext()) {
			// The destination is attached to the current switch
			// Find the device whose IP address is dstIp
			Iterator<? extends IDevice> IDev =
					LoadBalancer.deviceManagerService.queryDevices(
							MacAddress.NONE, VlanVid.ZERO, dstIp, IPv6Address.NONE,
							DatapathId.NONE, OFPort.ZERO
					);
			if (IDev != null && IDev.hasNext()) {
				// Find the attachment points of the device
				SwitchPort[] dest = IDev.next().getAttachmentPoints();
				for (int i = 0; i < dest.length; i++) {
					// Take only the attachment point with the current switch
					if (dest[0].getNodeId().equals(sw)) {
						outPort = dest[0].getPortId();
						break;
					}
				}
			}
		}
		// The port is specified in the route
		while(iterator.hasNext()) {
			NodePortTuple entry = iterator.next();
			if (entry.getNodeId().equals(sw)) {
				outPort = entry.getPortId();
				break;
			}
			if (iterator.hasNext()) {
				// Skip the input port
				iterator.next();
			}
		}
		return outPort;
	}
}
