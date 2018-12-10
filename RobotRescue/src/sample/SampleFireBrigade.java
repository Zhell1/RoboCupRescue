package sample;

import static rescuecore2.misc.Handy.objectsToIDs;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.plaf.synth.SynthDesktopIconUI;

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
    static int borne_1_damage = 33;	// split at X %
    static int borne_2_damage = 66;	// split at X % a second time
    static int borne_nbagents = 2;		// pour nbagents on building -> {0, 1 (/borne), 2} //for example if value is 2 => split between 0 and {1} / {2+} agents
    static int borne_lowwater = 20;	// split under X %
    
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
     * 		- the fire's fierceness		3 			firefiercenessCasted(Building b)	-> 1,2,3 	(no intact)
     * 		- the building's damage 	3 			damageCasted(Building b) 			-> 0,1,2 	(no intact) split 33% / 66%, original value in [1,100] 
     * 		- low_water()				2 			getLowWaterCasted() 				-> 0,1
     * 		- requiremove				2 			getRequireMoveCasted(Building b)	-> 0,1
     * 		- distance au refuge		2 			getDistRefugeCasted(Building b)		-> 0,1
     * 		- total area of building	3 			getTotalAreaCasted(Building b) 		-> 0,1,2
     * 		- get neighbours 			2 			getNbNeighboursCasted(Building b) 	-> 0,1
     * 		- nbAgentsonBuilding (!)	3 			GetNbAgentsOnBuildingCasted(Building b)-> 0,1,2 

     * 		=> 3^4 * 2^4 = 1296 états
     * 
     * remarques:
     * 
     *      requiremove (précédemment nommé 'distance à l'agent') = important pour qu'il apprenne à rejoindre les autres agents si ca vaut le coup de se déplacer
     * 
     * 		on  peut calculer d'abord sur la liste des batiments visibles autour,
     * 		si il y en a pas on calcule sur la liste globale
     * 
     * 		on peut aussi multiplier la Q valeur par la distance à l'agent avant de choisir
     * 		idem pour la distance au refuge
     * 
     * autres variables considérées:
     *    	  // - the building's composition		3 possible values
     * 		  // - the building's size				3 possible values
     * 
     **/
    
  //  static private Map<String,Integer> states = new HashMap<>(); // ex: "forcefeu" => 2
    
    static private Map<String,Float> Qtable = new HashMap<>();		// ex : "state_3_1_0_1_0_2_1_1"
    
    static private Map<EntityID,Integer> Hashmap_distrefuge = new HashMap<>();
    
    //don't change that
    private static boolean preprocessingdone = false;
    private static int analyzedborne_nbneighbours; //preprocessing
    private static int analyzedborne_distrefuge;
    private static int analyzedborne_1_totalarea;
    private static int analyzedborne_2_totalarea;
    
    private String actionstate = null; //state description of the action being done or null if no action being done


    private long start;

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
        
        if(preprocessingdone == false)
        	preprocessMap();
        
        start = System.currentTimeMillis();

    }
    void preprocessMap() {
        // **** preprocessing of map *****
        System.out.println("********preprocessing map *****");

        /*
        //calculate diagonal distance of the map
		Pair<Pair<Integer, Integer>, Pair<Integer, Integer>> worldbounds = model.getWorldBounds();
		Pair<Integer, Integer> pointA = worldbounds.first();
		Pair<Integer, Integer> pointB = worldbounds.second();
		int x1 = pointA.first();
		int y1 = pointA.second();
		int x2 = pointB.first();
		int y2 = pointB.second();
		int diagonaldistmap = (int) Math.sqrt((y2 - y1) * (y2 - y1) + (x2 - x1) * (x2 - x1));
        */
		
        //calculate    analyzedborne_nbneighbours
        int nbneighbours_mean_size = 0;
        int nbneighbours_nbval = 0;
        int nbneighbours_valmax = 0;
        
        int distrefuge_mean_size = 0;
        int distrefuge_nbval = 0;
        int distrefuge_valmax = 0;
        
        int totalarea_mean = 0;
        int totalarea_nbval = 0;
        int totalarea_minval = -1;
        int totalarea_maxval = 0;
        
        
        for (StandardEntity building : model.getEntitiesOfType(StandardEntityURN.BUILDING)){
        	List<EntityID> neighbourslist;
			if(building instanceof Building){
				
				//nb neighbours
        		neighbourslist = ((Building) building).getNeighbours();
				int listsize = neighbourslist.size();
				nbneighbours_mean_size += listsize;
				nbneighbours_nbval++;
				if(listsize > nbneighbours_valmax) nbneighbours_valmax = listsize;
				
				//distance au refuge
				int distmin = -1;
				//EntityID closestRefuge;
				for (EntityID refugeID : refugeIDs) {
					int distrefuge = model.getDistance(building.getID(), refugeID);
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
					if(distmin > distrefuge_valmax) distrefuge_valmax = distmin; //max ok
					Hashmap_distrefuge.put(building.getID(), distmin);
				} else { //no refuge found
					Hashmap_distrefuge.put(building.getID(), -1); // -1 means no refuge found 
				}
				
				//total area
				int totalarea = ((Building) building).getTotalArea();
				totalarea_mean += totalarea;
				totalarea_nbval++;
				if(totalarea > totalarea_maxval) totalarea_maxval = totalarea; //max
				if(totalarea_minval == -1) totalarea_minval = totalarea; //init min
				if(totalarea < totalarea_minval) totalarea_minval = totalarea; //min
			}
        }
        //neighbours
        nbneighbours_mean_size = nbneighbours_mean_size / nbneighbours_nbval;
        //definie la borne comme tu veux now
        analyzedborne_nbneighbours = nbneighbours_mean_size;  //TODO verifier si c'est bien ou si il faut modif
        if(analyzedborne_nbneighbours == nbneighbours_valmax && nbneighbours_valmax >= 2) analyzedborne_nbneighbours = nbneighbours_valmax -1; //sinon la borne est inutile //par sécurité mais peut-être pas utile
        System.out.println("Mean_nbneighbours = "+nbneighbours_mean_size+"\t\tvalmax = "+nbneighbours_valmax);
        System.out.println("chosen borne = "+analyzedborne_nbneighbours);
        
        //refuge dist
        distrefuge_mean_size = distrefuge_mean_size / distrefuge_nbval;
        //definie la borne comme tu veux now
        analyzedborne_distrefuge = distrefuge_mean_size;  //TODO verifier si c'est bien ou si il faut modif
        System.out.println("\nMean_distrefuge = "+distrefuge_mean_size+"\t\tvalmax = "+distrefuge_valmax);
        System.out.println("chosen borne = "+analyzedborne_distrefuge);
        
        //totalarea
        totalarea_mean = totalarea_mean / totalarea_nbval;
        //definie la borne comme tu veux now
        analyzedborne_1_totalarea = totalarea_minval+(totalarea_maxval-totalarea_minval)/3;
        analyzedborne_2_totalarea = totalarea_minval+2*(totalarea_maxval-totalarea_minval)/3;
        System.out.println("\nMean_totalarea = "+totalarea_mean+"\t\tvalmin = "+totalarea_minval+"\tvalmax = "+totalarea_maxval);
        System.out.println("chosen bornes = "+analyzedborne_1_totalarea+", "+analyzedborne_2_totalarea);
        
        //TODO : ça serait BEAUCOUP mieux de faire tout ça avec des quantiles pour avoir plus d'entropie
        // pour faire ça, stocker toutes les valeurs trouvées dans un array puis faire un sort()
        // ensuite regarder à partir de la .length la valeur à 34% par exemple et prendre ça comme estimation du quantile "< 33%"
        
        System.out.println("__________________________________\n");
    	preprocessingdone = true;
    }
    
    //give building, get state, ex : "state_3_1_0_1_0_2_1_1"
    private String getStateFromBuilding(Building b) {
    	String sout = "state";
        /* 
        * 		DESCRIPTION OF STATE	NB VALUES		FUNCTION TO GET IT					RETURN		COMMENTS
        * 
        * 		- the fire's fierceness		3 			firefiercenessCasted(Building b)	-> 1,2,3 	(no intact)
        * 		- the building's damage 	3 			damageCasted(Building b) 			-> 0,1,2 	(no intact) split 33% / 66%, original value in [1,100] 
        * 		- low_water()				2 			getLowWaterCasted() 				-> 0,1
        * 		- requiremove				2 			getRequireMoveCasted(Building b)	-> 0,1
        * 		- distance au refuge		2 			getDistRefugeCasted(Building b)		-> 0,1
        * 		- total area of building	3 			getTotalAreaCasted(Building b) 		-> 0,1,2
        * 		- get neighbours 			2 			getNbNeighboursCasted(Building b) 	-> 0,1
        * 		- nbAgentsonBuilding (!)	3 			GetNbAgentsOnBuildingCasted(Building b)-> 0,1,2 
        * 
        *  //autre idée: mettre le nbagents et le nbvoisins en full (pas de cast), ça change selon la map mais pas grave
        *  // retirer low_water, damage, 
        */
    	
    	sout += "_" +getFireFiercenessCasted(b);
    	sout += "_" +damageCasted(b) ;
    	sout += "_" +getLowWaterCasted();
    	sout += "_" +getRequireMoveCasted(b);
    	sout += "_" +getDistRefugeCasted(b);
    	sout += "_" +getTotalAreaCasted(b);
    	sout += "_" +getNbNeighboursCasted(b);
    	sout += "_" +getNbAgentsOnBuildingCasted(b);
    	
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
    	
    	if(totalarea < analyzedborne_1_totalarea) return 0;
    		
    	if(totalarea < analyzedborne_2_totalarea) return 1;
    	//else
    	return 2;
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
    
    private static int getNbAgentsOnBuildingCasted(Building b) {
    	int nbagents = getNbFightersOnBuilding(b.getID());
    	
    	if(nbagents == 0) return 0;
    	
    	if(nbagents < borne_nbagents) return 1;
    	
    	else return 2;
    	
    }
    
    private static int getNbNeighboursCasted(Building b){
    	List<EntityID> neighbourslist = b.getNeighbours();
    	if(neighbourslist == null) return 0;
    	
    	if(neighbourslist.size() == 0) return 0;
    	
    	if(neighbourslist.size() <= analyzedborne_nbneighbours) return 0; // < ou <= se discute, on a choisit <=
    	//else
    	return 1;
    }
    
    private int getLowWaterCasted() {
        FireBrigade me = me();
        if (me.isWaterDefined()){
        	int water = me.getWater();
        	if(water < borne_lowwater)
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
    		System.out.println("getQvalue() : state with -1, return 0 ("+state+")");
    		return (float) -1.0;
    	}
    	Float qvalue = Qtable.get(state);
    	if(qvalue == null)
    		return (float) 0.0;
    	else return qvalue;
    }
    
    static Set<EntityID> buildingsOnFire = new HashSet<EntityID>(); //only unique elements
    
    static List<Building> lastBuildingState = new ArrayList<Building>(); // don't use it directly, use the following fonctions:
    //update the state and makes sure there are no duplicates
    // synchronized makes sure there is not conccurent access between threads
    synchronized static private Building lastBuildingState_UpdateBuildingOrGet(Building bupdate, int action, StandardWorldModel mymodel) {
    	//action 0 -> update
    	//action 1 -> get
    	if(action == 0) {
	    	//on commence par supprimer l'élément précédent de même ID si il existe
	    	//we use iterator to avoid ConcurrentModificationException
	   		Iterator<Building> iterator = lastBuildingState.iterator();
			while (iterator.hasNext()) {
				// iterator.next() => l'élément courant
	    		if(iterator.next().getID().getValue() == bupdate.getID().getValue()) {
	    			iterator.remove(); // On supprime l'élément courant
	    		}
			}
			
	    	//on ajoute le nouveau
			if(bupdate != null)
				lastBuildingState.add(bupdate);
			return null;
    	}
    	//action 1 => get
    	else if(action == 1) {
    		//first we search if the building can be seen from the local view
    		Building b1 = (Building) mymodel.getEntity(bupdate.getID());
    		Collection<StandardEntity> allbuildings = mymodel.getEntitiesOfType(StandardEntityURN.BUILDING);
    		if(b1 != null && b1.isFierynessDefined()) {
    			boolean contains = false;
				for(StandardEntity buildingi : allbuildings) {
    				if(buildingi.getID() == bupdate.getID() && ((Building)buildingi).isOnFire()) //TODO regarde ce que ça donne
    					contains= true;
    			}
				
    			System.out.println("building "+b1.getID()+" found in LOCAL view, fieryness : "+b1.getFieryness()+", temperature : "+b1.getTemperature()+", contains : "+contains);
    			
    			//if we find it then we also update it
    			//------------ copypasted ---------------
	    			//on commence par supprimer l'élément précédent de même ID si il existe
	    	    	//we use iterator to avoid ConcurrentModificationException
	    	   		Iterator<Building> iterator = lastBuildingState.iterator();
	    			while (iterator.hasNext()) {
	    				// iterator.next() => l'élément courant
	    	    		if(iterator.next().getID().getValue() == b1.getID().getValue()) {
	    	    			iterator.remove(); // On supprime l'élément courant
	    	    		}
	    			}
	    	    	//on ajoute le nouveau
	    			if(b1 != null)
	    				lastBuildingState.add(b1);
    			//---------------- end ------------------
	    		if(contains == false)
	    			buildingsOnFire.remove(b1.getID()); //vérifié ok
	    		
	    		return b1;
    		}
            
    		//if we cannot have the building from local view, then get the last shared state 
        	//we use iterator to avoid ConcurrentModificationException
     		Iterator<Building> iterator = lastBuildingState.iterator();
    		while (iterator.hasNext()) {
    			Building b = iterator.next();
    			if (b != null && b.getID().getValue() == bupdate.getID().getValue()) {
    				//System.out.println("building "+bupdate.getID()+" from shared : "+b.getFullDescription());
    				System.out.println("building "+bupdate.getID()+" from shared, new_fieryness = "+b.getFieryness()+", temperature="+b.getTemperature());
        			return b;
        		}
    			
    		}
    		//if we found nothing we return null, should not happen
        	return null;
    	}
    	//never remove building from lastBuildingState !!!!!!!!!!!!! just update them
    	//we use only 1 function for update AND get because we need the synchronize keyword
    	// on both functions at the same time. could be done cleaner with actual locks.	
    	// or maybe  a synchronized static class ???
    	return null;
    }
    
    
    private float rewardcumul = 0;

    @Override
    protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    
    	
    	//TODO générer la reward des agents avant d'effacer le building par extinguishing(me()...)
    	
    	// dans un premier temps : reward = surface du batiment lorsqu'on arrive à l'éteindre
    	// aussi si on a plus d'eau (car sinon on peut ne jamais avoir de reward)
    	// on pondère la surface par le différence de fierceness entre le moment où on à commencé à éteindre et la nouvelle valeur
    	// si on batiment est en feu il à une valeur 2 par exemple, eteint il à 1 par exemple, auquel cas on donne la reward: surface*(2-1)+1
    	// le +1 sert de reward minimale, par exemple si on a surface*(2-2+1)+1, on obtient au moins une reward de 1 pouravoir éteint un batiment (et donc réduit la réduction du score future)
    	//TODO verifier si quand on éteint un feu de 1 il passe à 5 (qui renvoit un par firefiercenessCasted() ) et si c'est problématique pour le calcul de la reward
    	

	//	System.out.println(me().getID()+"\tNB BUILDINGS ON FIRE = "+buildingsOnFire.size()); //vérifié ok
    	
    	EntityID previousbuildingid = extinguishing.get(me().getID());
    	//System.out.println("previousbuildingid = "+previousbuildingid);
		FireBrigade me = me();
    	if(previousbuildingid != nullBuilding) {
    		//we get the last state of this building we got
    		Building previousbuilding = this.lastBuildingState_UpdateBuildingOrGet((Building)(model.getEntity(previousbuildingid)), 1, model);
    		//System.out.println(previousbuildingid+" isfierynessdefined : "+previousbuilding.isFierynessDefined());
    		System.out.println("previousbuilding = "+previousbuilding.getID()+", isOnFire() = "+previousbuilding.isOnFire()+", fieryness = "+previousbuilding.getFieryness());
    		//is the building extinguished ?           // or are we out of water? -> not a good idea, I comment it
    		//if(previousbuilding.isOnFire() == false ){ //|| (me .isWaterDefined() && me.getWater() == 0)) {
    		//si on voit que le building est éteint ou que quelqu'un l'a déjà éteint
    		
    		// REPRENDRE ICI, AJOUTER LASTBUILDINGSTATE
    		
    		if(previousbuilding.isOnFire() == false ||  ! buildingsOnFire.contains(previousbuilding.getID()) ) { //vérifié ok
    		//if( ! buildingsOnFire.contains(previousbuilding)) {
    			buildingsOnFire.remove(previousbuilding.getID()); //vérifié ok
    			this.lastBuildingState_UpdateBuildingOrGet(previousbuilding, 0, model); //remove
    			System.out.println("I REMOVE "+previousbuilding+" FROM ONFIRE LIST");
//    			System.out.println(me().getID()+"\tNB BUILDINGS ON FIRE = "+buildingsOnFire.size());
    			int newfieryness = this.getFireFiercenessCasted(previousbuilding);
    			float multiplier = 1;
    			if(newfieryness == 1) multiplier = 2/3; // 1/3 destroyed => 2/3 intact
    			if(newfieryness == 2) multiplier = 1/3; //only 1/3 intact
    			if(newfieryness == 3) multiplier = 0; //nothing intact
    			float reward = 1 + previousbuilding.getTotalArea()*multiplier; // +1 to get a reward even if the building is too much destroyed
				
    			long elapsedtime = System.currentTimeMillis()-start;
    			elapsedtime /= 1000; //in seconds
    			rewardcumul += reward;
    			reward = rewardcumul / elapsedtime;
    			
    			//TODO diviser la reward par le nombre d'agents qui étaient dessus ?
    			// sinon il se mettent tous sur ceux où il y a deja des agents car ca donne une reward facile ?
    			
    			//TODO
    			/*
    			 * remarque rapport :il faut passer en reward cumulée divisée par le temps cumulé
    			 * sinon ils apprenent juste à choisir de grosses reward (gros batiments)
    			 * mais pas spécialement à être efficace  ?
    			 * mais normalement non car si il fait des petits batiments pleins de fois
    			 * ca lui rapporte autant de qvalue que de faire un gros batiment une fois non ?
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
=> ca peut être pertinent de mettre dans l'état une valeur de la surface des voisins (en 3 ou 4 valeurs, après préprocessing de la carte pour l'ensemble des surfaces des voisins des batiments)
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
    		//	oldfieryness = -1;
    			actionstate = null;
    			extinguishing.replace(me().getID(), nullBuilding); 
    		}
            else if (me.isWaterDefined() && me.getWater() > 0) { // else building not extinguished and we still have some water, so let's keep extinguishing it !
            	EntityID next = previousbuilding.getID();
            	Logger.info("Extinguishing " + next);
                sendExtinguish(time, next, maxPower);
                sendSpeak(time, 1, ("Extinguishing " + next).getBytes());
                System.out.println("Keep Extinguishing building " + next);
                extinguishing.replace(me.getID(), previousbuilding.getID()); //added
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
        
        /*
         * added, ce comportement focalise tous les agents sur le batiment 249 (le plus gros de la map test)
         *  il vont tous se mettre à  distance du batiment et l'éteindre en même temps dès qu'il prend feu
         *  pour l'utiliser comment les 3 derniers sous-comportements correspondant de la fonction think
         */
        /*
        // Find all buildings that are on fire
        Collection<EntityID> all = getBurningBuildings();
        // Can we extinguish any right now?
        for (EntityID next : all) {
        	System.out.println(next+" => "+getNbFightersOnBuilding(next)+" pompiers dessus"); //added
        	
        	
          if (model.getDistance(getID(), next) <= maxDistance && next.getValue() == 249) { //modified
                Logger.info("Extinguishing " + next);
                sendExtinguish(time, next, maxPower);
                sendSpeak(time, 1, ("Extinguishing " + next).getBytes());
                System.out.println("Extinguishing " + next);
                extinguishing.replace(me, next); //added
                return;
            }
        }
        if(model.getDistance(getID(), new EntityID(249)) > maxDistance) {
	        List<EntityID> path = search.breadthFirstSearch( me().getPosition(), new EntityID(249));
	        sendMove(time, path);
	        return;
        }
        else {
        	List<EntityID> path = planPathToFire(new EntityID(249));
            if (path != null) {
                Logger.info("Moving to target");
                sendMove(time, path);
                return;
            }
        }
	        
        //*/
        
        
        //////////////////////////////
        ////// TODO IMPORTANT ////////
        //////////////////////////////
        // dès qu'on commence à eteindre un batiment faire:
        // actionstate = getStateFromBuilding(Building b).clone();
        
        //choisit building qui maximize la Qvaleur
        Collection<Building> all = getBurningBuildings();
        
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
        	sout += "\t\t"+next.getID()+"\t"+state+"\t"+qvalue+"\n";
        	projectionTable.put(next, qvalue);
        }
        if(all.size()>0) System.out.println(sout);
        
        //now get the best building (highest qvalue)
        if(projectionTable.size() > 0) {
        	List<Float> sortedvalues = new ArrayList<Float>( projectionTable.values());
        	Collections.sort(sortedvalues, Collections.reverseOrder()); //we want the highest qvalue first -> descending order
        	
	    	// Do what you need with sortedKeys.
        	float bestqvalue = sortedvalues.get(0); //best q value
        	//now get all the buildings corresponding to it
        	List<Building> bestbuildings = new ArrayList<Building>();
        	for(Building b : projectionTable.keySet()) {
        		if(projectionTable.get(b) == bestqvalue)
        			bestbuildings.add(b);
        	}
        	
	    	bestBuilding = bestbuildings.get(0); //TODO change this by a random selection


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
				actionstate = getStateFromBuilding(bestBuilding);
				
				
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
				   System.out.println("ERROR COULD NOT PLAN PATH TO FIRE:");
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
        

        /*
        // Find the first building that is on fire and extinguish it
        Collection<EntityID> all = getBurningBuildingsID();
        // Can we extinguish any right now?
        for (EntityID next : all) {
        	if(getNbFightersOnBuilding(next) > 0)
        		System.out.println(next+" => "+getNbFightersOnBuilding(next)+" pompiers dessus"); //added
			// all is already sorted so we can just take the first one that is accessible
          if (model.getDistance(getID(), next) <= maxDistance) { //modified
                Logger.info("Extinguishing " + next);
                sendExtinguish(time, next, maxPower);
                sendSpeak(time, 1, ("Extinguishing " + next).getBytes());
                System.out.println("Extinguishing building " + next);
                extinguishing.replace(me1, getBuildingfromId(next)); //added
                return;
            }
        }
        
     
        Collection<EntityID> all1 = getBurningBuildingsID();
        // Plan a path to a fire
        for (EntityID next : all1) {
            List<EntityID> path = planPathToFire(next);
            if (path != null) {
                Logger.info("Moving to target");
                sendMove(time, path);
                return;
            }
        }
        
        List<EntityID> path = null;
        Logger.debug("Couldn't plan a path to a fire.");
        path = randomWalk();
        Logger.info("Moving randomly");
        sendMove(time, path);
        */
        
    }

    @Override
    protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
        return EnumSet.of(StandardEntityURN.FIRE_BRIGADE);
    }
    
    private static int damageCasted(Building b) {
        if(b != null) {
            if(b.isBrokennessDefined()) {
            	int damage = b.getBrokenness(); //entre 0 et 100
            	//System.out.println("damage = "+damage); 
            	if(damage < borne_1_damage)
            		return 0;
            	if(damage < borne_2_damage)
            		return 1;
            	else
            		return 2;
            }
            return 0;
        }
        return 0;
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
    private static int getFireFiercenessCasted(Building b) {
        if(b != null) {
        	//il se peut qu'on ait réussi à éteindre le building entre temps
        	if(b.isOnFire() == false){
        		//System.out.println("firefiercenessCasted() : ERROR BUILDING NOT ON FIRE for "+b);
        		//return -1;
        		System.out.println("ETEINT, fieryness = "+b.getFieryness());
        		return -1;
        	}
        	//remarque: b.isFierynessDefined() ne marche pas et renvoie tjr false, alors que getfieryness marche !

        	int fieryness = b.getFieryness(); //entre 0 et 8, seul 1,2,3 nous intéressent
        	//System.out.println("fieryness = "+fieryness+" for "+b); 

        	if(fieryness == 0 || fieryness == 4)
        		System.out.println("firefiercenessCasted() : ERROR FIERYNESS SHOULD NOT BE "+fieryness+" for "+b);
        	
        	if(fieryness == 0) return 0;
        	if(fieryness == 1 || fieryness == 5) return 1; //TODO vérifier si ça c'est une bonne idée ou pas
        	if(fieryness == 4 ) return 1;
        	if(fieryness == 2 || fieryness == 6) return 2;
        	if(fieryness == 3 || fieryness == 7 || fieryness == 8) return 3;
        }
        return 0;
    }

    private Collection<Building> getBurningBuildings() {
        Collection<StandardEntity> e = model.getEntitiesOfType(StandardEntityURN.BUILDING); //renvoi bien tous les building, vérifié ok
        List<Building> result = new ArrayList<Building>();
        for (StandardEntity next : e) {
            if (next instanceof Building) {
                Building b = (Building)next;    
               // getRealValue(b);
               if (b.isOnFire()) {// && b.isFierynessDefined()) {
                    result.add(b);
                    this.lastBuildingState_UpdateBuildingOrGet(b, 0, model); //action 0 = update
                    buildingsOnFire.add(b.getID());
                }
            }
        }
        /*
        String sout = "";
        for (EntityID bof : buildingsOnFire)
        	sout += bof+", ";
        if (sout.length() > 1)
        	System.out.println("BUILDINGS ON FIRE = "+sout);
        	*/
        System.out.println(me().getID()+"\t NB BUILDINGS ON FIRE : "+this.buildingsOnFire.size());
        // Sort by distance
      //  Collections.sort(result, new DistanceSorter(location(), model));
       // return result;
        EntityID[] copy_buildingsOnFire = buildingsOnFire.toArray(new EntityID[buildingsOnFire.size()]); //for concurrentaccess
        List<Building> l = new ArrayList<Building>();
        for(EntityID curr : copy_buildingsOnFire) {
        	 //Building b = (Building) model.getEntity(curr); //not working
        	Building b = (Building) this.lastBuildingState_UpdateBuildingOrGet((Building)model.getEntity(curr), 1, model);
        	//if (b.getFieryness() == 1 || b.getFieryness() == 2 || b.getFieryness() == 3)
        	if(b.isOnFire())
        		l.add(b); 
        	//test
        	/*
        	if(l.size() > 0) {
        		b = l.get(l.size()-1);
        		System.out.println("getBurningBuildings, "+b+" fieryness = "+b.getFieryness());
        	}
        	*/
        }
       // Collections.sort(l, new DistanceSorter(location(), model)); //no need to sort with qvalues
        return l;
        
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
        Collection<StandardEntity> targets = model.getObjectsInRange(target, maxDistance/2); //-> /2 verified, very important        
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
    
    
    
}
