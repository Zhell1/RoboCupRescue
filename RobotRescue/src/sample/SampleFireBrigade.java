package sample;

import static rescuecore2.misc.Handy.objectsToIDs;

import rescuecore2.Timestep;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;

import javax.swing.plaf.synth.SynthDesktopIconUI;

import rescuecore.Handy;
import rescuecore.RescueConstants;
import rescuecore.RescueObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.log.Logger;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Road;

/**
   A sample fire brigade agent.
 */

public class SampleFireBrigade extends AbstractSampleAgent<FireBrigade> {
    
    //----------------------------- PARAMETERS ---------------------------------
    
    //Qlearning parameters
    static float _alpha = (float) 0.1;
    
    // bornes (uniquement pour les éléments qu'on ne peut pas analyser depuis la map en préprocessing)
    // you can change these if you know what you are doing (original values: 33, 66, 2, 20)
    //static int borne_1_damage = 33;	// split at X %
    //static int borne_2_damage = 66;	// split at X % a second time
  //  static int borne_nbagents = 2;		// pour nbagents on building -> {0, 1 (/borne), 2} //for example if value is 2 => split between 0 and {1} / {2+} agents
    static int borne_lowwater = 50;	// split under X %
    
    //--------------------------------------------------------------------------
    
    private static final String MAX_WATER_KEY = "fire.tank.maximum";
    private static final String MAX_DISTANCE_KEY = "fire.extinguish.max-distance";
    private static final String MAX_POWER_KEY = "fire.extinguish.max-sum";

    private int maxWater;
    private static int maxDistance;
    private int maxPower;
    
    //firebrigade -> building
    static private Map<EntityID, EntityID> extinguishing = new HashMap<>(); //added
    static private EntityID nullBuilding = new EntityID(-1); //added
    
    @Override
    public String toString() {
        return "Sample fire brigade";
    }
    
    /*
     * choix: 
     * 
     * Qlearning : qui sert juste à trouver le score d'un batiment = intérets d'éteindre ce batiment en feu
     * 
     * reward : reward global à chaque retour dans le fonction think() si il y avait un choix de batiment à l'itération
     * 			précédente
     * 
     * reward local = 	en temps réels
     * 					juste la surface des batiments, pas de survie des individus
     * 					0 si le feu est pas éteint
     * 				  	positif si le feu à été éteint
     * 						=> dépend de la surface + du nb de voisins
     * 												  (ou surface des voisins)
     * 
     * description des états:
     * 
     * 		DESCRIPTION OF STATE	NB VALUES		FUNCTION TO GET IT					RETURN		COMMENTS
     * 
     * 		- the fire's fierceness		3 			getFireFierynessCasted(Building b)	 -> 1,2,3 	(no intact)
 	 *		- low_water()				2 			getLowWaterCasted() 				 -> 0,1
     * 		- requiremove				2 			getRequireMoveCasted(Building b)	 -> 0,1
     * 		- distance au refuge		2 			getDistRefugeCasted(Building b)		 -> 0,1
     * 		- total area of building	2 			getTotalAreaCasted(Building b) 		 -> 0,1
 	 *		- agentsonBuilding (!)		2 			getAgentsOnBuildingCasted(Building b)-> 0,1
     *      - building's composition	3			b.getBuildingCode();				 -> 0,1,2
     *   //   - NB neighbours				???			b.getNeighbours();					 -> ???		(petit et très disparate sur la Qtable, peu de valeurs)
	 *
     * 		=> 3*2*2*2*2*2*3*??? = 288 *??? états    (??? étant petit et très disparate sur la Qtable, peu de valeurs)
     * 
     * 		il faut en preprocessing:
     * 				moyenne des distances au refuge
     * 				médiane des total_area
     * 
     * remarques:
     * 
     *      requiremove (précédemment nommé 'distance à l'agent') = important pour qu'il apprenne à rejoindre les autres agents si ca vaut le coup de se déplacer
     * 
     * 		on  peut calculer d'abord sur la liste des batiments visibles autour,
     * 		si il y en a pas on calcule sur la liste globale
     * 
     * 		on peut diviser la Qvaleur par la distance à l'agent avant de choisir
     * 
     * autres variables considérées:
     * 		  // - the building's size				3 possible values
     * 
     * previous used:
     *      - the building's damage 	3 			damageCasted(Building b) 			-> 0,1,2 	(no intact) split 33% / 66%, original value in [1,100] 
     * 		- get neighbours 			2 			getNbNeighboursCasted(Building b) 	 -> 0,1
     *      
     *         
     * 		TODO utiliser température ?
 	 * 
     *  autre idée: mettre le nbagents et le nbvoisins en full (pas de cast), ça change selon la map mais pas grave
	 *      retirer low_water, damage ?
     *
     */
    
  //  static private Map<String,Integer> states = new HashMap<>(); // ex: "forcefeu" => 2
    
    static private Map<String,Float> Qtable = new HashMap<>();		// ex : "state_3_1_0_1_0_2_1_1"
    
    static private Map<EntityID,Integer> Hashmap_distrefuge = new HashMap<>();
    
    //don't change that
    private static boolean preprocessingdone = false;
  //  private static int analyzedborne_nbneighbours; //preprocessing
    private static int analyzedborne_distrefuge;
  //  private static int analyzedborne_1_totalarea;
  //  private static int analyzedborne_2_totalarea;
    private static int analyzedborne_totalarea;
    
    private String actionstate = null; //state description of the action being done or null if no action being done

   // static Set<Building> buildingsOnFire = new HashSet<Building>(); //only unique elements
    static Set<Building> buildingsOnFire = new ConcurrentHashSet<Building>();  
    static Map<EntityID, Integer> lastSeenFieryness = new ConcurrentHashMap<EntityID, Integer>();
    
    private float rewardcumul = 0;

    private long start;

    

    //add this to start.sh before deleting old logs: 
    //  cat logs/kernel.log | grep -a "Score" | tail -n 2 | head -n 1 | cut -d ' ' -f 5 >> score_log_thomas
    
    //initialisation
    @Override
    protected void postConnect() {
    	
        super.postConnect();
        model.indexClass(StandardEntityURN.BUILDING, StandardEntityURN.REFUGE,StandardEntityURN.HYDRANT,StandardEntityURN.GAS_STATION);
        maxWater = config.getIntValue(MAX_WATER_KEY);
        maxDistance = config.getIntValue(MAX_DISTANCE_KEY);
        maxPower = config.getIntValue(MAX_POWER_KEY);
        Logger.info("Sample fire brigade connected: max extinguish distance = " + maxDistance + ", max power = " + maxPower + ", max tank = " + maxWater);
        
        //init extinguishing, tableau des autres pompiers
        for (StandardEntity fighter : model.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
        	extinguishing.put(((FireBrigade) fighter).getID(), nullBuilding);
        }
        
        if(preprocessingdone == false) {
            //load previous Qtable
            Qtable_loadfromfile();
            //preprocess the map
        	preprocessMap();
        }
        
        
        
        start = System.currentTimeMillis();

    }

    
    void preprocessMap() {
        // **** preprocessing of map *****
        System.out.println("********preprocessing map *****");
        System.out.println("__________________________________");
        
        /* il faut en preprocessing:
         * 				moyenne des distances au refuge			analyzedborne_distrefuge 
         * 				médiane des total_area					analyzedborne_totalarea
         */   
        int distrefuge_mean_size = 0;
        int distrefuge_nbval = 0;
        List<Integer> list_totalarea = new ArrayList<Integer>();
        
        for (StandardEntity buildingi : model.getEntitiesOfType(StandardEntityURN.BUILDING)){
			if(buildingi instanceof Building){
				Building b = (Building) buildingi;
				
				//distance au refuge
				int distmin = -1;
				//EntityID closestRefuge;
				for (EntityID refugeID : refugeIDs) {
					int distrefuge = model.getDistance(b.getID(), refugeID);
					if(distmin == -1){
						distmin = distrefuge; //init
						//closestRefuge = refugeID; //init
					}
					if(distrefuge < distmin){
						distmin = distrefuge;
						//closestRefuge = refugeID;
					}
				}
				if(distmin != -1) { //si on a trouvé un refuge accessible
					distrefuge_mean_size += distmin;
					distrefuge_nbval++;
					Hashmap_distrefuge.put(b.getID(), distmin);
				} else { //no refuge found
					Hashmap_distrefuge.put(b.getID(), -1); // -1 means no refuge found 
				}
				
				// total area
				list_totalarea.add(b.getTotalArea());	
			}
        }
        //distance au refuge -> moyenne (car moyenne maximise déjà l'entropie)
        distrefuge_mean_size = distrefuge_mean_size / distrefuge_nbval;
        analyzedborne_distrefuge = distrefuge_mean_size; 
        System.out.println("analyzedborne_distrefuge =\t\t"+analyzedborne_distrefuge);
        
        //analyzedborne_totalarea -> prendre la médiane (maximise entropie)
        Collections.sort(list_totalarea);
        //System.out.println(list_totalarea);
        analyzedborne_totalarea = list_totalarea.get((int)(list_totalarea.size()/2+1));
        System.out.println("analyzedborne_totalarea =\t\t"+analyzedborne_totalarea);
        
        System.out.println("__________________________________\n");
    	preprocessingdone = true;
    }
    
    //give building, get state, ex : "state_3_1_0_1_0_2_1_1"
    private String getStateFromBuilding(Building b) {
    	String sout = "state";
    	
    	sout += "_"+getFireFierynessCasted(b);
    	sout += "_"+getLowWaterCasted();
    	sout += "_"+getRequireMoveCasted(b);			//-> PB pas pris en compte dans le reward
    	sout += "_"+getDistRefugeCasted(b);				//ok
    	sout += "_"+getTotalAreaCasted(b);
    	sout += "_"+getAgentsOnBuildingCasted(b);
    	sout += "_"+b.getBuildingCode();				//ok
   // 	sout += "_"+getNbNeighbours(b);
       
    	return sout;
    }

   
    static private int getNbFightersOnBuilding(EntityID building) {
    	int counter = 0;
    	for (EntityID firebrigadeID : extinguishing.keySet()) {
    		if (extinguishing.get(firebrigadeID).equals(building)) // vérifié ok
    			counter++;
    	}
    
    	return counter;
    }
    
    private static int getTotalAreaCasted(Building b) {
    	
    	int totalarea = b.getTotalArea();
    	
    	if(totalarea < analyzedborne_totalarea) return 0;
    	//else
    	return 1;
    }
    
    //return 0 si proche du refuge, 1 si loin
    // si on avait des blockades il faudrait recalculer un chemin accessible, mais comme on en a pas on peut récup la distance du preprocessing
    private static int getDistRefugeCasted(Building b) {
		int distrefuge = Hashmap_distrefuge.get(b.getID()); // dist au refuge le plus proche
		if(distrefuge == -1){ //devrait pas arriver
			System.out.println("getDistRefugeCasted(): ERROR REFUGE NOT FOUND (value -1)");
			//TODO il faut peut-être aussi ajouter les FireStation ? on peut récolter de l'eau dans les 2 sur toutes les cartes ?? a verif
			return 1;
		}
    	if(distrefuge < analyzedborne_distrefuge) return 0;
    	//else
    	return 1;
    }
    
    private static int getAgentsOnBuildingCasted(Building b) {
    	int nbagents = getNbFightersOnBuilding(b.getID())-1; //remove myself
    	if(nbagents == 0) return 0;
    	//else
    	return 1;
    }
    //only gives an estimate since we use the model
    private int getNbNeighbours(Building b){
    	List<EntityID> neighbourslist = b.getNeighbours(); // attention CONTIENT AUSSI LES ROADS
    	int nbout = 0;
    	for(EntityID entityid : neighbourslist) {
    		StandardEntity entity = model.getEntity(entityid);
    		if(entity instanceof Building) {
    			nbout++;
    		}
    	}
    	return nbout;
    }
    
    
    private int getLowWaterCasted() {
        FireBrigade me = me();
        if (me.isWaterDefined()){
        	int water = me.getWater();
        	if(water < maxWater*borne_lowwater/100)
        		return 1;
        	else
        		return 0;
        }
    	return 0;
    }
    
    private int getRequireMoveCasted(Building b) {
        if (model.getDistance(getID(), b.getID()) > maxDistance)
        	return 1;
        else
        	return 0;
    }
    
    //try not to use this because it is costly,  only use it when you cannot do otherwise
    Building getBuildingfromId(EntityID id) {
    	Building bout = null;
        Collection<StandardEntity> e = model.getEntitiesOfType(StandardEntityURN.BUILDING);
        //List<Building> result = new ArrayList<Building>();
        
        for (StandardEntity next : e) {
            if (next instanceof Building) {
            	if(next.getID().getValue() == id.getValue())
            		bout = (Building)next;
            }
        }
        if(bout == null) System.out.println("getBuildingfromId() : ERROR BUILDING "+id.getValue()+" NOT FOUND");
    	return bout;
    }
  
    float getQvalue(String state) {
    	//if states contains a -1, then the state is no longer valid, can happen if we get a building on fire and then try to get fieryness but it is already extinguished, then fieryness = -1
    	if (state.contains("-1")) {
    		System.out.println("getQvalue() : state with -1, return -1 ("+state+")");
    		return (float) -1.0;
    	}
    	Float qvalue = Qtable.get(state);
    	if(qvalue == null)
    		return (float) 0.0;
    	else return qvalue;
    }

    @Override
    protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    	
    	/*
    	 * 
    	 * 
    	 * 
    	 * 
    	 * 
    	 * 
    	 * 
    	 * 
    	 * TODO init les qvaleurs à 1 ? et faire un tirage aléatoire 
    	 * pour faire plus d'exploration
    	 * 
    	 * 
    	 * diviser par le temps la reward:
    	 * utiliser les timesteps ou une mesure de temps interne à la plateforme
    	 * 
    	 * 
    	 * faire un tirage uniforme pour le choix du building selon les qvaleur
         *	float bestqvalue = sortedvalues.get(0); //
    	 * 
    	 * 
    	 * 
    	 * 
    	 * 
    	 *   sout += "_"+getRequireMoveCasted(b);			//-> PB pas pris en compte dans le reward
    	 *      devrait aider en divisant par le temps si on commence à compter quand on choisit (et pas quand on éteint)
    	 * 
    	 * 
    	 * 
    	 * 
    	 */
    	//System.out.println("******* timestep "+time+" *******");
    	//System.out.println("changed = "+changed);
    	model.merge(changed); 
    	
    	FireBrigade me = me();
    	EntityID previousbuildingid  = extinguishing.get(me().getID());
    	int nbagentsonbuilding = 0;
    	
    	//si on était en train d'éteindre un building
    	if(previousbuildingid != nullBuilding) {
    		nbagentsonbuilding=getNbFightersOnBuilding(previousbuildingid); //not perfect, will change for each agent but better than nothing (ex 3,2,1 as each agents removes himself), should still help converge
    		System.out.println("previousbuilding = "+previousbuildingid);
	    	//si on observe encore le building qu'on éteignait, on le met à jour
	    	if(changed.getChangedEntities().contains(previousbuildingid)) {
	        	Building previousbuilding = (Building) model.getEntity(previousbuildingid);
	    		//we need to remove because add to a set doesn't change it if it already exists
		    	this.buildingsOnFire.remove(previousbuilding);
		    	if(previousbuilding.isOnFire()){
		    		this.buildingsOnFire.add(previousbuilding); //update shared data with new value
		    		System.out.println("updated previous building "+previousbuildingid+" from changeset");
		    	}
	    	}
        	Building previousbuilding = (Building) model.getEntity(previousbuildingid);
	    	String sout = "previousbuilding = "+previousbuilding.getID()+", isOnFire() = "+previousbuilding.isOnFire();
	    	if(previousbuilding.isFierynessDefined()) sout+= ", fieryness = "+previousbuilding.getFieryness();
	    	System.out.println(sout);
	    	//si le building est éteint où qu'un autre agent à indiqué qu'il était éteint en le retirant:
	    	if(previousbuilding.isOnFire() == false ||  ! buildingsOnFire.contains(previousbuilding.getID()) ) {
	    		this.buildingsOnFire.remove(previousbuilding);
    			System.out.println("removed "+previousbuildingid+" from onfire list");
    	        System.out.println("Nb buildings on fire : "+this.buildingsOnFire.size());
    			
    			//now we do the Qlearning
    	        int newfieryness = this.getFireFierynessCasted(previousbuilding);
    	        
    			float multiplier = 1;
    			if(newfieryness == 1) multiplier = 2/3; // 1/3 destroyed => 2/3 intact
    			if(newfieryness == 2) multiplier = 1/3; //only 1/3 intact
    			if(newfieryness == 3) multiplier = 0; //nothing intact
    			float reward = 1 + previousbuilding.getTotalArea()*multiplier; // +1 to get a reward even if the building is too much destroyed
				reward = reward / nbagentsonbuilding ; //nbagentsonbuilding est une estimation entre [nbagents, 1]
    			//TODO diviser par le temps mis à éteindre le building
 
    			//TODO diviser la reward par le nombre d'agents qui étaient dessus ?
    			// sinon il se mettent tous sur ceux où il y a deja des agents car ca donne une reward facile
    			
    			//TODO
    			/*
    			 * remarque rapport :il faut passer en reward divisée par le temps 
    			 * sinon ils apprenent juste à choisir de grosses reward (gros batiments)
    			 * mais pas spécialement à être efficace  ?
    			 * on peut crorie que si il fait des petits batiments pleins de fois
    			 * ca lui rapporte autant de qvalue que de faire un gros batiment une fois ?
    			 * => non ! car c'est l'expected reward qu'il cherche à prédire,
    			 * et un gros batiment aura forcément une expected reward plus grande
    			 * or si il prédit bien on ajoute +0 et ça change pas
    			 */
    			
				//TODO prendre en compte cela:
				/*
				Buildings in danger are near buildings which
				are not on fire, but that may catch fire if fire fi is not extinguished. For each building in
				danger, the utility function returns the amount of points lost if the building in danger catches
				fire.
				=> prendre en compte la surface du batiment * fieryness + somme( surface voisin*fieryness_voisin, for all voisins)
				=> ca peut être pertinent de mettre dans l'état une valeur de la surface des voisins (en 3 ou 4 valeurs, 
mais on  ne peut pas le faire en preprocessing car les batiments voisins ne sont pas connus
				*/
    			
    			if(actionstate == null) System.out.println("ERROR ACTIONSTATE IS NULL");
    			else System.out.println("actionstate = "+actionstate);
    			
    			float oldqvalue = getQvalue(actionstate);
    			System.out.println("\told qvalue = "+oldqvalue); 
			
       			// Q learning equation:
    			// V(st) <- V(st) + alpha * (Reward_t - V(st))
    			float newval = oldqvalue + _alpha * (reward - oldqvalue);
    			Qtable.put(actionstate, newval); 

                System.out.println("\tGot reward of " + reward);
                System.out.println("\tnew qvalue = "+newval);
    			
    			//reset values now
    			actionstate = null;
    			extinguishing.replace(me().getID(), nullBuilding); 
    			
    			//now we save the Qtable to a file
    			Qtable_savetofile();
    			
	    	}
			//System.out.println(me().getID()+"\tNB BUILDINGS ON FIRE = "+buildingsOnFire.size());

	    	// else if building not extinguished and we still have some water, so let's keep extinguishing it !
	    	else if (me.isWaterDefined() && me.getWater() > 0) { 
            	EntityID next = previousbuildingid;
            	Logger.info("Extinguishing " + next);
                sendExtinguish(time, next, maxPower);
                sendSpeak(time, 1, ("Extinguishing " + next).getBytes());
                System.out.println("Keep Extinguishing building " + next);
                extinguishing.replace(me.getID(), previousbuildingid); //added
                return;
    		}
         }
    		
    	
		//reset values now
		//oldfieryness = -1;
		actionstate = null;
		extinguishing.replace(me().getID(), nullBuilding); 
    	
    	
        if (time == config.getIntValue(kernel.KernelConstants.IGNORE_AGENT_COMMANDS_KEY)) {
            // Subscribe to channel 1
            sendSubscribe(time, 1);
        }
        for (Command next : heard) {
            Logger.debug("Heard " + next);
        }
        FireBrigade me1 = me();
        // Are we currently filling with water?
        if (me1.isWaterDefined() && me1.getWater() < maxWater && location() instanceof Refuge) {
            Logger.info("Filling with water at " + location());
            sendRest(time);
            return;
        }
        // Are we out of water?
        if (me1.isWaterDefined() && me1.getWater() == 0) {

        	//TODO j'ai ajouté ça, à vérifier
			//reset values now
			actionstate = null;
			extinguishing.replace(me().getID(), nullBuilding); 
			
            // Head for a refuge
            List<EntityID> path = search.breadthFirstSearch(me().getPosition(), refugeIDs);
            if (path != null) {
                Logger.info("Moving to refuge");
                sendMove(time, path);
                return;
            }
            else {
                Logger.debug("Couldn't plan a path to a refuge.");
                path = randomWalk();
                Logger.info("Moving randomly");
                sendMove(time, path);
                return;
            }
        }
        
        //choisit building qui maximize la Qvaleur
        Set<Building> all = new HashSet<Building>();
        //on commence par y mettre les buildings en feu qu'on observe
        for(EntityID entityid : changed.getChangedEntities()) {
        	StandardEntity entityi = model.getEntity(entityid);
        	if (entityi instanceof Building) {
        		Building buildingi = (Building) entityi;         		
        		if(buildingi.isOnFire()) {
        			//if already contained we remove it (to update just after) because what we observe is always better
        			// and add() does not change it in a set if it is already there
        			if(buildingsOnFire.contains(buildingi)) {
        				buildingsOnFire.remove(buildingi);
        			}
        			all.add(buildingi);
        			buildingsOnFire.add(buildingi);
        		}
        	}
        }
        //puis on y ajoute ceux observés par les autres agents
        Set<Building> buildingsOnFire_copy = new HashSet<Building>(this.buildingsOnFire); //better for concurrentmodificationexception ?
        for(Building buildingi :buildingsOnFire_copy) {
        	if( ! all.contains(buildingi))
        		all.add(buildingi);
        }
        //Set<EntityID> all = objectsToIDs(buildings);
        
        //System.out.println("Nb buildings on fire : "+this.buildingsOnFire.size());
        
        Building bestBuilding = null;

        //table (qvalue, état) des batiments en feu trouvés
        Map<Building, Float> projectionTable = new HashMap<>();
        
        String sout ="";
        if(all.size()>0) sout+=all.size()+" buildings burning:\n";
        
        //for each building, associate a qvalue
        sout = "\tQvalues : \n";
        for (Building next : all) {
        	String state = this.getStateFromBuilding(next);
        	float qvalue = this.getQvalue(state);
        	//on pondère les qvalues par la distance
        	float dist = model.getDistance(me().getPosition(), next.getID())+1; //+1 in case dist = 0
        	System.out.println("dist = "+dist);
        	//qvalue = qvalue / (dist/100000); //TODO est-ce une bonne idée ? distance pas forcément très pertinent dans ce cas
        	if(qvalue >= 0)
        		sout += "\t\t"+next.getID()+"\t"+state+"\t"+qvalue+"\n";
        	projectionTable.put(next, qvalue);
        }
        if(all.size()>0) System.out.print(sout);
        
        //now get the best building (highest qvalue)
        if(projectionTable.size() > 0) {
            System.out.println("Nb buildings on fire : "+this.buildingsOnFire.size());
        	List<Float> sortedvalues = new ArrayList<Float>( projectionTable.values());
        	Collections.sort(sortedvalues, Collections.reverseOrder()); //we want the highest qvalue first -> descending order
        	
	    	// Do what you need with sortedKeys.
        	float bestqvalue = sortedvalues.get(0); //best q value
        	
        	if(bestqvalue >= 0) { // important because sometimes we have concurrent access and getQvalue returns -1 (=building already extinguished)
	        	//now get all the buildings corresponding to it
	        	List<Building> bestbuildings = new ArrayList<Building>();
	        	for(Building b : projectionTable.keySet()) {
	        		if(projectionTable.get(b) == bestqvalue)
	        			bestbuildings.add(b);
	        	}
	        	
		    	bestBuilding = bestbuildings.get(0); //TODO change this by a random selection
        	} else {
        		bestBuilding = null;
        	}


	    	//System.out.println("found best building "+bestBuilding.getID().getValue()+" with qvalue "+sortedKeys.get(0));
        }
        
        //TODO passer le tirage du bestbuilding en un tirage aléatoire projeté dans les qvalue des buildings
        // pour ne pas toujours prendre le best mais varier un peu plus

        
        /*
         * PROBLEME: CHOIX A FAIRE
         * soit on dit que l'état "nbagentsdessus" change quand l'agent décide d'aller sur un batiment mais en est encore loin (pathfinding)
         * et ça peut influencer d'autres à aller dessus même s'ils sont loin, mais l'agent peut ensuite changer d'avis et prendre un nouveau batiment qui est moins loin 
         * et vient de prendre feu, auquel cas ça peut biaiser le choix précédents des autres agents qui l'attendent sur le batiment initialement choisis.
         * 
         * donc 2 choix indépendants à faire:
         * CHOIX possible 1 : l'agent à la droit de changer d'avis en cours de pathfinding ou pas => dans un 1er temps on choisit oui
         * CHOIX possible 2 : l'agent annonce qu'il se met sur un building au début de son choix (avant pathfinding) soit quand il commence à envoyer de l'eau
         * 						-> dans un premier temps on choisit que c'est quand il envoi de l'eau
         */
        
       // double dist = model.getDistance(new EntityID(960), new EntityID(274));
        //System.out.println("dist(960, 274) = "+dist+" / "+maxDistance);
        
        if (bestBuilding != null) {
	        // si le building est déjà accessible on commence à l'éteindre
			if (model.getDistance(me().getPosition(), bestBuilding.getID()) <= maxDistance) { //modified
				
				extinguishing.replace(me().getID(), bestBuilding.getID()); //added
				actionstate = getStateFromBuilding(bestBuilding); // SUPER IMPORTANT dès qu'on commence à éteindre un building
				
				
				Logger.info("Extinguishing " + bestBuilding.getID());
				sendExtinguish(time, bestBuilding.getID(), maxPower);
				sendSpeak(time, 1, ("Extinguishing " + bestBuilding.getID()).getBytes());
				System.out.println("Starts Extinguishing building " + bestBuilding.getID());
				return;
			}
			else { //sinon on y va 
			   List<EntityID> path = planPathToFire(bestBuilding.getID());
			   if (path != null && path.get(0) != me().getPosition()) {
			       Logger.info("Moving to target");
			       sendMove(time, path);
			       System.out.println("Moving to target "+bestBuilding.getID()+" : "+path);
			       // if you want the agent to be free to reevaluate and change to another building, don't do anything more here
			       //    I.E. don't announce extinguishing.replace(me1, bestBuilding) and don't force him to go the building on the next iteration (as is already)
			       return;
			   } else {
				   System.out.println("******** ERROR COULD NOT PLAN PATH TO FIRE: *******");
				   double dist = model.getDistance(me().getPosition(), bestBuilding.getID());
			       System.out.println("dist("+me().getPosition()+", "+bestBuilding.getID()+") = "+dist+" / "+maxDistance);
			   }
			}
        }
        //si aucun batiment en feu, on se déplace aléatoirement
        List<EntityID> path = null;
        Logger.debug("Couldn't plan a path to a fire.");
        path = randomWalk();
        Logger.info("Moving randomly");
       // System.out.println("couldn't plan path to a fire, moving randomly");
        sendMove(time, path); 
    }

    @Override
    protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
        return EnumSet.of(StandardEntityURN.FIRE_BRIGADE);
    }
    /*
     * 		 Fierceness		    	Meaning
     * 		-----------------|-----------------------
				0					Intact building.
				1					Small fire.
				2					Medium fire.
				3					Huge fire.
				4					Not on fire, but damage by the water.
				5					Extinguished, but slightly damage.
				6					Extinguished, but moderately damage.
				7					Extinguished, but severely damage.
				8					Completely burned down.
		et on doit s'inspirer de la façon dont le vrai score est calculé pour avoir une bonne convergence
		donc on prends:
	     	Fierceness 				Score rules
				-------------|----------------------
				0 					No penalty
				1 or 5				1/3 of the building’s area is considered destroyed.
				4					Water damage, also 1/3 of the building’s area is considered destroyed.
				2 or 6				2/3 of the building’s area is considered destroyed.
				3, 7 or 8			The whole building is considered destroyed.
     */
    // not used but can be useful to check if the values you get from b.get***() are the same
    // as the ones from the actual fulldescription
    // it was already the case for the fieryness so it works fine without it
    private static int getRealValue(Building b) {
    	System.out.println("\n-----------------------------------");
    	String description = b.getFullDescription();
    	System.out.println(description+"\n\t|\n\tv");
    	String [] stab = description.split("urn:rescuecore2.standard:property:fieryness = ");
    	for(String stabi : stab) {
    		System.out.println(stabi);
    	}
    	System.out.println("fieryness found     = "+(stab[1].substring(0, 1))); //(0,1) because only 1 char for fieryness
    	System.out.println("fieryness attribute = "+b.getFieryness());
    	int fieryness = -1;

    	System.out.println("-----------------------------------\n");
    	return fieryness;
    }
    private static int getFireFierynessCasted(Building b) {
        if(b != null) {
        	//il se peut qu'on ait réussi à éteindre le building entre temps
        	/*if(b.isOnFire() == false){
        		//System.out.println("firefiercenessCasted() : ERROR BUILDING NOT ON FIRE for "+b);
        		//return -1;
        		System.out.println(b+" ETEINT");
        		return -1;
        	}*/
        	//System.out.println(b+" getfirefierynesscasted()");
        	int fieryness;
	        if(b.isFierynessDefined()) {
	        	fieryness = b.getFieryness(); //entre 0 et 8, seul 1,2,3 nous intéressent
	        	lastSeenFieryness.put(b.getID(), fieryness);
	        }else {
	        	//ceci permet de passer outre les model.merge(changed) qui remette à undefined les fieryness
	        	fieryness = lastSeenFieryness.get(b.getID());
	        }
        	//System.out.println("fieryness = "+fieryness+" for "+b); 

        	if(fieryness == 0 || fieryness == 4)
        		//System.out.println("firefiercenessCasted() : ERROR FIERYNESS SHOULD NOT BE "+fieryness+" for "+b);
        	
        	if(fieryness == 0) return 0;
        	if(fieryness == 1 || fieryness == 5) return 1; //TODO vérifier si ça c'est une bonne idée ou pas
        	if(fieryness == 4 ) return 1;
        	if(fieryness == 2 || fieryness == 6) return 2;
        	if(fieryness == 3 || fieryness == 7 || fieryness == 8) return 3;
        }
        return 0;
    }

    private List<EntityID> planPathToFire_original(EntityID target) {
        // Try to get to anything within maxDistance of the target
        Collection<StandardEntity> targets = model.getObjectsInRange(target, maxDistance); // should be maxdist/2
        if (targets.isEmpty()) {
            return null;
        }
        //return search.breadthFirstSearch(me().getPosition(), objectsToIDs(targets));
        List<EntityID> path = search.breadthFirstSearch(me().getPosition(), objectsToIDs(targets));
        System.out.println("path found from "+me().getPosition()+" to "+target+" : "+path);
        return path;
    }
    
    //thomas: only works with buildings that are in the line of sight, will bug if asked to target to a building the agent cannot see
    private List<EntityID> planPathToFire(EntityID target) {
        // Try to get to anything within maxDistance of the target
    	//get all the objets that allow access to the target
        Collection<StandardEntity> targets = model.getObjectsInRange(target, maxDistance); //-> /2 verified, very important        
        if (targets.isEmpty()) {
        	System.out.println("NULL PATH from "+me().getPosition()+" to "+target.getValue());
            return null;
        }
        //getobjectsinrange doesn't work so we need to retest each target
        List<StandardEntity> toremove = new ArrayList<StandardEntity>();
		for(StandardEntity currtarget : targets) {
        	double dist = model.getDistance(target, currtarget.getID());
        	if(dist > maxDistance)
        		toremove.add(currtarget); //stock in another to avoid concurrentmodifexception
        }
		for(StandardEntity currremove : toremove) //now remove all
			targets.remove(currremove);
			
        //return search.breadthFirstSearch(me().getPosition(), objectsToIDs(targets));
        List<EntityID> path = search.breadthFirstSearch(me().getPosition(), objectsToIDs(targets));
        System.out.println("path found from "+me().getPosition()+" to "+target);//+" : "+path+"\t"+objectsToIDs(targets));
        return path;
   
    }

    
  void Qtable_savetofile() {
	  //write to file 
	  try{
	    File fileTwo=new File("Qtable_saved.txt");
	    FileOutputStream fos=new FileOutputStream(fileTwo);
	        PrintWriter pw=new PrintWriter(fos);

	        for(Map.Entry<String,Float> m :Qtable.entrySet()){
	            pw.println(m.getKey()+"="+m.getValue());
	        }

	        pw.flush();
	        pw.close();
	        fos.close();
	    }catch(Exception e){}
  }
  void Qtable_loadfromfile() {
	   //read from file 
	    try{
	        File toRead=new File("Qtable_saved.txt");
	        FileInputStream fis=new FileInputStream(toRead);

	        Scanner sc=new Scanner(fis);

	        HashMap<String,Float> mapInFile=new HashMap<String,Float>();

	        //read data from file line by line:
	        String currentLine;
	        while(sc.hasNextLine()){
	            currentLine=sc.nextLine();
	            //now tokenize the currentLine:
	            StringTokenizer st=new StringTokenizer(currentLine,"=",false);
	            //put tokens to currentLine in map
	            mapInFile.put(st.nextToken(), Float.parseFloat(st.nextToken()));
	        }
	        fis.close();
	        sc.close();
	        
	        //now we clear the current Qtable and load the one from the file into it
	        Qtable.clear();
	        Qtable.putAll(mapInFile);

	        //print All data in MAP
	        for(Map.Entry<String,Float> m :mapInFile.entrySet()){
	            System.out.println(m.getKey()+" : "+m.getValue());
	        }
	    }catch(Exception e){}
  }
    
}
