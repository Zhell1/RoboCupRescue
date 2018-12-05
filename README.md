# RoboCupRescue
Apprentissage Multi-Agents par renforcement

__________________

Le code de l'agent FireFighter se trouve dans : 

RobotRescue/src/sample/SampleFireBrigade.java

et le dossier RobotRescue/ correspond au projet sous eclipse


__________________
pour lancer la simulation, dans boot/ lancer:
```
 ./start.sh ../maps/gml/test/
```
(gml/test est la petite carte)

dans un autre terminal lancer: (ausi dans boot/) : /sampleagent.sh 
ou alors lancer les agents depuis eclipse

__________________

états du Qlearning:
```
  * description des états:
     * 
     * 		DESCRIPTION OF STATE  	NB_VALUES		FUNCTION TO GET IT					RETURN		COMMENTS
     * 
     * 		- the fire's fierceness		3 			firefiercenessCasted(Building b)	-> 1,2,3 	(no intact)
     * 		- the building's damage		3 			damageCasted(Building b) 			-> 0,1,2 	(no intact) split 33% / 66%, original value in [1,100] 
     * 		- low_water()				2 			getLowWaterCasted() 				-> 0,1
     * 		- requiremove				2 			getRequireMoveCasted(Building b)	-> 0,1
     * 		- distance au refuge		2 			getDistRefugeCasted(Building b)		-> 0,1
     * 		- total area of building	3 			getTotalAreaCasted(Building b) 		-> 0,1,2
     * 		- get neighbours		2 			getNbNeighboursCasted(Building b) 	-> 0,1
     * 		- nbAgentsonBuilding (!)	3 			GetNbAgentsOnBuildingCasted(Building b)-> 0,1,2 

     * 		=> 3^4 * 2^4 = 1296 états
```
__________________

pour désactiver les blockades:

dans maps/gml/test/config/collapse.cfg:

```
# Whether to create road blockages or not
collapse.create-road-blockages: false
```
__________________

pour augmenter la fréquence des feux augmenter le lambda dans:
dans maps/gml/test/config/ignition.cfg
```
# The lambda value of the Poisson distribution used to choose how many fires to ignite per timestep
ignition.random.lambda: 0.5
```

__________________

Liens utiles:
javadoc

https://www.ida.liu.se/~TDDD10/docs/javadoc/sample/SampleFireBrigade.html
components https://www.ida.liu.se/~TDDD10/docs/javadoc/rescuecore2/components/
scores https://www.ida.liu.se/~TDDD10/docs/javadoc/rescuecore2/score/
