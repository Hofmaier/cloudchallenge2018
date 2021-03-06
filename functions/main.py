import logging 
import os
from google.cloud import bigquery

DATASET_ID = 'cloud-hackathon-team-athena:flight_messages'
REGION = os.environ['REGION']

def counters(request):
    """HTTP Cloud Function.
    Args:
        request (flask.Request): The request object.
        <http://flask.pocoo.org/docs/0.12/api/#flask.Request>
    Returns:
        The response text, or any set of values that can be turned into a
        Response object using `make_response`
        <http://flask.pocoo.org/docs/0.12/api/#flask.Flask.make_response>.
    """
    
    path = request.path
    airport = path.split('/')[-1]
    if airport == 'counters':
        raise Exception('No airport specified')
    
    countByAirport = query_bigquery(airport)  
    
    logging.info('[%s] Count for airport \'%s\' = %d', REGION, airport, countByAirport)
    return str(countByAirport)
    
    

def query_bigquery(airport_code):
    # Instantiates a client
    bigquery_client = bigquery.Client()
    query_job = bigquery_client.query("SELECT COUNT(*) FROM flight_messages.raw_flight_messages WHERE airport = '" + airport_code + "'")
    rows = query_job.result() 

    row_list = []
    for result in rows:
        row_list.append(result)
    first_row = row_list[0]
    return first_row.values()[0]    
    