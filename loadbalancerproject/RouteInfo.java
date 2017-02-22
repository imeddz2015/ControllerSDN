package net.floodlightcontroller.loadbalancerproject;

import java.util.Comparator;

import org.projectfloodlight.openflow.types.IPv4Address;

import net.floodlightcontroller.routing.Path;

public class RouteInfo {
	public int hopNumber;
	public IPv4Address destination;
	public Path path;
	
	public RouteInfo(int hopNumber, IPv4Address destionation, Path path) {
		this.hopNumber = hopNumber;
		this.destination = destionation;
		this.path = path;
	}
	
	@Override
	public String toString() {
		return hopNumber + ": " + path.toString()+" "+destination.toString();
	}
	
	public static class RouteInfoComparator implements Comparator<RouteInfo> {
		@Override
		public int compare(RouteInfo r1, RouteInfo r2) {
			return r1.hopNumber - r2.hopNumber;
		}
	}
}
