import * as messaging from "messaging";
import { me as companion } from "companion";
import { settingsStorage } from "settings";
import asap from "fitbit-asap/companion";


// global variables
let command_id = 0;
let nextInfo = "";
let timeSchedule = "";
let isConnectionOn = null;
let isFirstRun = null;
// constant variables
var LOOPINTERVAL_SHORT = 3000;   // we can change the looping interval arbitrarily
var KEY = "first_run";
var ITEM = "done";

// for debugging
const debug = false
// var num_received = 0;

// check if this is the first time to run the app
if (settingsStorage.getItem(KEY) == null) {
  debug && console.log("this is the first time we run the app");
  isFirstRun = true;
  settingsStorage.setItem(KEY, ITEM);
} else {
  debug && console.log("this is a reboot");
  isFirstRun = false;
}

companion.addEventListener("unload", () => {
  console.log("Companion unloading");
});

// Constantly check: 1)the connection status and update the status to the database; 2)if there's new command sent from the phone
setInterval(function () {
  updateConnStatus();
  checkCommand();
  updateInterval();
  updateSchedule();
}, LOOPINTERVAL_SHORT);

// Listen for the onmessage event
asap.onmessage = message => {
  debug && console.log("Companion received: " + JSON.stringify(message));
  if (typeof message.type != "undefined") {
    debug && checkReceived()
    postData(message, 'http://localhost:8080/store_data.php');
  } else if (typeof message.reasons != "undefined") {
    debug && checkReceived()
    postData(message, 'http://localhost:8080/save_reasons.php');
  } else if (typeof message.response != "undefined") {
    debug && checkReceived()
    postData(message, 'http://localhost:8080/save_response.php');
  } else if (typeof message.notif != "undefined") {
    debug && checkReceived()
    postData(message, 'http://localhost:8080/save_notif.php');
  }
}

messaging.peerSocket.onmessage = function (evt) {
  let message = evt.data;
  // debug && console.log("Companion received: " + JSON.stringify(message));
  if (typeof message.prompt != "undefined") {
    postData(message, 'http://localhost:8080/send_prompt.php');
  }
}

function updateInterval() {
  fetch('http://localhost:8080/retrieve_data.php', { method: 'GET' }).then(function (response) {
    return response.text();
  }).then(function (text) {
    if (text.trim() != nextInfo.trim()) {
      debug && console.log("Got new survey info from server: " + text);
      if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
        messaging.peerSocket.send(text);
        nextInfo = text;
      }
    }
  }).catch(function (error) {
    debug && console.log(error);
  });
}

function updateSchedule() {
  fetch('http://localhost:8080/retrieve_time.php', { method: 'GET' }).then(function (response) {
    return response.text();
  }).then(function (text) {
    if (text.trim() != timeSchedule.trim()) {
      debug && console.log("Got new schedule from server: " + text);
      if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
        messaging.peerSocket.send(text);
        timeSchedule = text;
      }
    }
  }).catch(function (error) {
    debug && console.log(error);
  });
}

function checkCommand() {
  // console.log("command id is: "+command_id);
  fetch('http://localhost:8080/check_command.php', { method: 'GET' }).then(function (response) {
    return response.text();
  }).then(function (obj) {
    var newObj = JSON.parse(obj);
    let id = newObj["id"];
    // console.log("id is: "+id);
    let command = newObj["command"];
    // if this is a reboot, then update the current command_id to be the latest id
    if (!isFirstRun) {
      if (id != false) command_id = id;
      isFirstRun = true;
    }
    if (id > command_id) {  // if the latest id is greater than current command_id, then deliver the message to watch
      command_id = id;
      let message = JSON.parse(JSON.stringify({ command, command_id }));
      debug && console.log("Got new command from phone: " + command);
      options = { "timeout": 60000 };
      asap.send(message, options);

    }
  }).catch(function (error) {
    debug && console.log(error);
  });
}

function updateConnStatus() {
  let curStatus = messaging.peerSocket.readyState == messaging.peerSocket.OPEN ? true : false;
  // console.log("Companion connection status: "+curStatus);
  if (isConnectionOn == null || isConnectionOn != curStatus) {
    isConnectionOn = curStatus;
    let status = curStatus ? 1 : 0;
    let timeStamp = Date.now();
    let message = { timeStamp, status };
    debug && console.log("Sent connection status: " + JSON.stringify(message));
    postData(message, 'http://localhost:8080/update_conn.php');
  }
}

function postData(obj, link) {
  let init = {
    method: 'POST',
    body: JSON.stringify(obj),
    headers: new Headers()
  };
  fetch(link, init).then(function (response) {
    return response.text();
  }).then(function (text) {
    debug && console.log("Got response from server!" + text);
  }).catch(function (error) {
    debug &&  console.log(error);
  });
}

function checkReceived() {
  num_received += 1
  console.log("Number of records received since start: " + num_received)
}


