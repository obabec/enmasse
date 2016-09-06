{ 
  generate(secure)::
  [
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "name": "messaging"
      },
      "spec": {
        "ports": [
          {
            "port": 5672,
            "protocol": "TCP",
            "targetPort": 5672
          }
        ],
        "selector": {
          "capability": "router"
        }
      }
    },
    {
      "apiVersion": "v1",
      "kind": "ReplicationController",
      "metadata": {
        "labels": {
          "name": "qdrouterd"
        },
        "name": "qdrouterd"
      },
      "spec": {
        "replicas": 1,
        "selector": {
          "name": "qdrouterd"
        },
        "template": {
          "metadata": {
            "labels": {
              "capability": "router",
              "name": "qdrouterd"
            }
          },
          "spec": {
            "containers": [
              {
                "image": "${QDROUTER_IMAGE}",
                "name": "master",
                "ports": [
                  {
                    "containerPort": 5672,
                    "protocol": "TCP"
                  }
                ]
              }
            ]
          }
        }
      }
    }
  ]
}
