package net.floodlightcontroller.loadbalancerproject;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.core.web.ControllerSummaryResource;
import net.floodlightcontroller.core.web.ControllerSwitchesResource;
import net.floodlightcontroller.core.web.LoadedModuleLoaderResource;
import net.floodlightcontroller.restserver.RestletRoutable;

public class LoadBalancerWebRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
		// Add some predefined REST resources available in the Floodlight framework
		// This resource shows some summary statistics on the controller
		router.attach("/controller/summary/json", ControllerSummaryResource.class);
		// This resource shows the list of modules loaded in the controller
		router.attach("/module/loaded/json", LoadedModuleLoaderResource.class);
		// This resource shows the list of switches connected to the controller
		router.attach("/controller/switches/json", ControllerSwitchesResource.class);
		
		/************** CUSTOM RESOURCES **************/
		// Subscription
        router.attach("/controller/subscribe/json", Subscribe.class);
        // Unsubscription
        router.attach("/controller/unsubscribe/json", Unsubscribe.class);        
        // Show the list of physical IP addresses associated with an anycast address
        router.attach("/controller/showlist/json", ShowList.class);
        /**********************************************/
        
		return router;
	}

	@Override
	public String basePath() {
		// The root path for the resources
		return "/lb";
	}

}
