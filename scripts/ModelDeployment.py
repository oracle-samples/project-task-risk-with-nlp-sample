"""
Copyright (c) 2022, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
"""
from re import L
import oci
import os
import sys
from oci.data_science.models import CreateModelDetails, Metadata, CreateModelProvenanceDetails, UpdateModelDetails, \
    UpdateModelProvenanceDetails
import shutil
import zipfile
from os.path import basename

# Need to modify with correct values before running the script
compartmentID="<YOUR OCI COMPARTMENT OCID>"
projectID="<YOUR ODS PROJECT OCID>"
modelDisplayName="Risk Predictor"
modelDescription="Risk Predictor Sample"
modelDeploymentDisplayName="Risk Predictor"
modelDeploymentDescription="Risk Predictor Sample"
modelDeploymentInstanceCount=1
modelDeploymentLBBandwidth=10  #mbps
modelDeploymentPreditLogID="<YOUR ODS DEPLOYMENT PREDICT LOG OCID>"
modelDeploymentPreditLogGroupID="<YOUR ODS DEPLOYMENT PREDICT LOG GROUP OCID>"
modelDeploymentAccessLogID="<YOUR ODS DEPLOYMENT ACCESS LOG OCID>"
modelDeploymentAccessLogGroupID="<YOUR ODS DEPLOYMENT ACCESS LOG GROUP OCID>"
modelDeploymentInstanceShapeName="VM.Standard2.1"  #<OCI VM SHAPE , FOR EXAMPLE: VM.Standard2.1>
modelZip = 'scripts/model.zip'
modelFile = 'artifact/export.pkl'
runtimeFile = 'artifact/runtime.yaml'
scoreFile = 'artifact/score.py'

config = oci.config.from_file()

# Initialize service client with default config file and set the timeout value to handle large file
data_science_client = oci.data_science.DataScienceClient(config=config, timeout=30 * 60)

#Prepare a model zip filewith the model pickle file, runtime file and score file.
def PrepareModelZip():
    #zip the model and runtime file
    zipObj = zipfile.ZipFile('scripts/model.zip', 'w', zipfile.ZIP_DEFLATED)
    zipObj.write(modelFile, basename(modelFile))
    zipObj.write(runtimeFile, basename(runtimeFile))
    zipObj.write(scoreFile, basename(scoreFile))
    zipObj.close()

    if os.path.exists(modelZip):
        print("Processing " + modelZip)
    else:
        sys.exit("No model.zip found") 

#Create a model and upload the artifact to this new model
def CreateAndUploadModel() :
    # Declare input/output schema for our model
    # It must be a valid json string
    input_schema = "{\"text\": { \"description\": \"Request to be classified\", \"type\": \"string\" }}";
    output_schema = "{\"prediction\": { \"description\": \"The prediction in which the text is classified\",\
                    \"type\": \"string\" }, \"probability\": { \"description\": \"The probability in which the text is classified\",\
                    \"type\": \"float\" }}";


    # Create a request and dependent object(s) for model.
    model_details = CreateModelDetails(
        compartment_id=compartmentID,
        project_id=projectID,
        display_name=modelDisplayName,
        description=modelDescription,
        input_schema=input_schema,
        output_schema=output_schema)
    # Send request to client
    model = data_science_client.create_model(create_model_details=model_details).data

    # # adding the artifact:
    with open(modelZip,'rb') as artifact_file:
        artifact_bytes = artifact_file.read()
        data_science_client.create_model_artifact(model.id, artifact_bytes, content_disposition='attachment; filename="'+ modelZip + '"')
    return model

#Update exisitng model deployment
def UpdateModeDeployment(modelId, modelDeploymentId) :
    # Update Exisiting Model deployment with the new model
    update_model_deployment_response = data_science_client.update_model_deployment(
        model_deployment_id=modelDeploymentId,
        update_model_deployment_details=oci.data_science.models.UpdateModelDeploymentDetails(
            display_name=modelDeploymentDisplayName,
            description=modelDeploymentDescription,
            model_deployment_configuration_details=oci.data_science.models.UpdateSingleModelDeploymentConfigurationDetails(
                deployment_type="SINGLE_MODEL",
                model_configuration_details=oci.data_science.models.UpdateModelConfigurationDetails(
                    model_id=modelId,
                    instance_configuration=oci.data_science.models.InstanceConfiguration(
                        instance_shape_name=modelDeploymentInstanceShapeName),
                    scaling_policy=oci.data_science.models.FixedSizeScalingPolicy(
                        policy_type="FIXED_SIZE",
                        instance_count=modelDeploymentInstanceCount),
                    bandwidth_mbps=modelDeploymentLBBandwidth))
        )
    )
    if update_model_deployment_response.status >= 200 and update_model_deployment_response.status < 300 :
        print("Model Deploymenbt Updated")
        if os.path.exists(modelZip):
            os.remove(modelZip)
        else:
            print("The model file does not exist") 
    else:
        print("Model Deploymenbt Update Failed:".join(update_model_deployment_response.headers))

#Create a model deployment with a deployed model.
def CreateModelDeployment(modelId):
    create_model_deployment_response = data_science_client.create_model_deployment(
        create_model_deployment_details=oci.data_science.models.CreateModelDeploymentDetails(
            project_id=projectID,
            compartment_id=compartmentID,
            model_deployment_configuration_details=oci.data_science.models.SingleModelDeploymentConfigurationDetails(
                deployment_type="SINGLE_MODEL",
                model_configuration_details=oci.data_science.models.ModelConfigurationDetails(
                    model_id=modelId,
                    instance_configuration=oci.data_science.models.InstanceConfiguration(
                        instance_shape_name=modelDeploymentInstanceShapeName),
                    scaling_policy=oci.data_science.models.FixedSizeScalingPolicy(
                        policy_type="FIXED_SIZE",
                        instance_count=modelDeploymentInstanceCount),
                    bandwidth_mbps=modelDeploymentLBBandwidth)),
            display_name=modelDeploymentDisplayName,
            description=modelDeploymentDescription,
            category_log_details=oci.data_science.models.CategoryLogDetails(
                access=oci.data_science.models.LogDetails(
                    log_id=modelDeploymentAccessLogID,
                    log_group_id=modelDeploymentAccessLogGroupID),
                predict=oci.data_science.models.LogDetails(
                    log_id=modelDeploymentPreditLogID,
                    log_group_id=modelDeploymentPreditLogGroupID)),
            )
        )

    # Get the data from response
    if create_model_deployment_response.status >= 200 and create_model_deployment_response.status < 300 :
        print("Model deployment request submitted, you can check the status of the creation process in OCI console.")
    else:
        print("Model deploymenbt Failed:".join(create_model_deployment_response.headers))

def GetModelDeployment(modelDeploymentId):
    get_model_deployment_response = data_science_client.get_model_deployment(
    model_deployment_id=modelDeploymentId)
    # Get the data from response
    if get_model_deployment_response.status >= 200 and get_model_deployment_response.status < 300 :
        print("Get Model Deployment Completed")
    else:
        print("Get Model Deploymenbt Failed:".join(get_model_deployment_response.headers))
    # Get the data from response
    print(get_model_deployment_response.data)

#List all model deployment in a project in a compartment. Assuming the display name is unique.
def ListModelDeployment():
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    list_model_deployments_response = data_science_client.list_model_deployments(
        compartment_id=compartmentID,
        display_name=modelDeploymentDisplayName,
        project_id=projectID,
        #lifecycle_state = "ACTIVE",
        limit=1)
    # Get the data from response
    if list_model_deployments_response.status >= 200 and list_model_deployments_response.status < 300 :
        # Get the data from response
        count = len(list_model_deployments_response.data)
        print('Total number of model deployment found: ' , count)
        # Assume only one match and return the model deployment id.
        if count < 1 or count > 1:
            return
        else:
            return list_model_deployments_response.data[0]
    else:
        print("List Model Deploymenbt Failed:".join(list_model_deployments_response.headers))
        raise Exception

#List all model in a project in a compartment. Assuming the display name is unique.
def ListModel():
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    list_models_response = data_science_client.list_models(
        compartment_id=compartmentID,
        project_id=projectID,
        display_name=modelDisplayName,
        # lifecycle_state="ACTIVE",
        limit=1)

    if list_models_response.status >= 200 and list_models_response.status < 300 :
        # Get the data from response
        count = len(list_models_response.data)
        print('Total number of model found: ' , count)
        # Assume only one match and return the model deployment id.
        if count < 1 or count > 1:
            return
        else:
            return list_models_response.data[0]
    else:
        print("List Model Failed:".join(list_models_response.headers))
        raise Exception

#Activate a model using model Id
def ActivateModel(modelId):
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    activate_model_response = data_science_client.activate_model(
        model_id=modelId)

    if activate_model_response.status >= 200 and activate_model_response.status < 300 :
        # Get the data from response
        print('Model Activated: ' , modelId)
        # Assume only one match and return the model deployment id.
    else:
        print("List Model Failed:".join(activate_model_response.headers))
        raise BaseException

#Activate a model deploymnet using model deployment id.
def ActivateModelDeployment(modelDeploymentId):
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    activate_model_deployment_response = data_science_client.activate_model_deployment(
        model_deployment_id=modelDeploymentId)

    if activate_model_deployment_response.status >= 200 and activate_model_deployment_response.status < 300 :
        # Get the data from response
        print('Model deployment activated :' ,modelDeploymentId)
    else:
        print("Model deployment activation failed:".join(activate_model_deployment_response.headers))
        raise Exception

#Deactivate a model deploy using a model deployment id
def DeactivateModelDeployment(modelDeploymentId):
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    deactivate_model_deployment_response = data_science_client.deactivate_model_deployment(
        model_deployment_id=modelDeploymentId)

    if deactivate_model_deployment_response.status >= 200 and deactivate_model_deployment_response.status < 300 :
        # Get the data from response
        print('Model deployment deactivated :' ,modelDeploymentId)
    else:
        print("Model deployment deactivation failed:".join(deactivate_model_deployment_response.headers))
        raise Exception

#Delete a model using a model id
def DeleteModel(modelId):
    # Send the request to service, some parameters are not required, see API
    # doc for more info
    delete_model_response = data_science_client.delete_model(
        model_id=modelId)

    if delete_model_response.status >= 200 and delete_model_response.status < 300 :
        # Get the data from response
        print('Model deleted', modelId)
    else:
        print("Model deletion failed:".join(delete_model_response.headers))
        raise Exception

def main():
    try:
        model = ListModel()
        modelDeployment = ListModelDeployment()
        PrepareModelZip()
        if modelDeployment is not None and modelDeployment.lifecycle_state=="ACTIVE"  :
            DeactivateModelDeployment(modelDeployment.id)
            modelDeployment = ListModelDeployment()
        if model is not None and model.lifecycle_state!="DELETED":
            print("Existing model found but inactive, delete existing model: ", model.id)
            DeleteModel(model.id)
        print("Create a new model...")
        model = CreateAndUploadModel()
        if model is not None:      
            if modelDeployment is not None:
                print("Existing model found, update existing model: ", modelDeployment.id)
                if modelDeployment.lifecycle_state == 'INACTIVE':
                    ActivateModelDeployment(modelDeployment.id)
                UpdateModeDeployment(model.id, modelDeployment.id )
            else:
                print('No model deployment found, creating new model')
                CreateModelDeployment(model.id)
    except Exception as e:
        print("Unable to update or create model or model deployment: ", e)

if __name__ == '__main__':
    main()