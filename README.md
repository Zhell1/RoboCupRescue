# RoboCupRescue
Apprentissage Multi-Agents



Notes:
__________________
pour lancer la simulation, dans boot/ lancer:
```
 ./start.sh ../maps/gml/test/
```
(gml/test est la petite carte)

dans un autre terminal lancer: (ausi dans boot/) : /sampleagent.sh 
ou alors lancer les agents depuis eclipse

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
