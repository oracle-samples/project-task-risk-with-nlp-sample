"""
Copyright (c) 2022, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
"""
import json
import os
from fastai.text.all import *
from fastai import *
from fastai.basics import *
from fastai.callback.all import *

model_name = 'export.pkl'

def load_model(model_file_name=model_name):
    if not model_file_name:
        return None
    model_dir = os.path.dirname(os.path.realpath(__file__))
    contents = os.listdir(model_dir)
    if model_file_name in contents:
        filepath = os.path.join(os.path.dirname(os.path.realpath(__file__)), model_file_name)
        with open(filepath, "rb") as file:
            try:
                learner = load_learner(filepath, cpu=True)
            except FileNotFoundError:
                print("File not found")
                return None
            except Exception as e:
                print(e)
                return None
            return learner
    else:
        raise Exception('{0} is not found in model directory {1}'.format(model_file_name, model_dir))

def predict(data, model=load_model()):
    if len(data) == 0:
        return {"error": "no data"}
    if type(data) is str:
        d = json.loads(data)
        txt = json.dumps(d['text'])
    if type(data) is dict:
        txt = data['text']
    pred = ""
    try:
        pred, i , probs = model.predict(txt)
        index = int(i.tolist())
        probability = probs[index]
    except Exception as e:
        print("Predition Error: " + e)
        pass
    return {"prediction": pred, "probability": probability.tolist()}