# RoboCupRescue
Apprentissage Multi-Agents par renforcement

__________________

Le code de l'agent FireFighter se trouve dans : 

RobotRescue/src/sample/SampleFireBrigade.java

et le dossier RobotRescue/ correspond au projet sous eclipse


__________________
pour lancer la simulation, à la racinte faire "ant" la première fois pour compiler
besoin de JAVA_HOME dans le .bashrc, ex:
export JAVA_HOME="/usr/lib/jvm/jdk1.8.0_171/jre/"

Ensuite dans boot/ lancer:
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
     * 		DESCRIPTION OF STATE  	    NB_VALUES		FUNCTION TO GET IT			  RETURN	COMMENTS
     * 
     *          - the fire's fierceness		3 		getFireFierynessCasted(Building b)	 -> 1,2,3 	(no intact)
     *		- low_water()			2 		getLowWaterCasted() 			 -> 0,1
     * 		- requiremove			2 		getRequireMoveCasted(Building b)	 -> 0,1
     * 		- distance au refuge		2 		getDistRefugeCasted(Building b)		 -> 0,1
     * 		- total area of building	2 		getTotalAreaCasted(Building b) 		 -> 0,1
     *	 	- agentsonBuilding (!)		2 		getAgentsOnBuildingCasted(Building b)	 -> 0,1
     *          - building's composition	3		b.getBuildingCode();			 -> 0,1,2
     *
     * 		=> 3*2*2*2*2*2*3 = 288 états 
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

bon nombre de ressources en ligne, en particulier sur la page de la compétition :
http://rescuesim.robocup.org/resources/documentation/
Avez-vous regardé dans la première section (agent simulation) ?


javadoc

https://www.ida.liu.se/~TDDD10/docs/javadoc/sample/SampleFireBrigade.html

components https://www.ida.liu.se/~TDDD10/docs/javadoc/rescuecore2/components/

scores https://www.ida.liu.se/~TDDD10/docs/javadoc/rescuecore2/score/
