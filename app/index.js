import clock from "clock";
import document from "document";
import { vibration } from "haptics";
import * as util from "../common/utils";
import { today } from 'user-activity';
import { display } from "display";
import { battery } from "power";
import { charger } from "power";
import { me } from "appbit";
import asap from "fitbit-asap/app";
import * as messaging from "messaging";
import { readFileSync, writeFileSync } from "fs";

// UI elements
var mainLayout = document.getElementById("main-layout");
var responseLayout = document.getElementById("response-layout");
var feedbackLayout = document.getElementById("feedback-layout");
var reasonList = document.getElementById("reason-list");
var timeLabel = document.getElementById("time-label");
var batteryLevel = document.getElementById("battery-percentage");
var batteryIcon = document.getElementById("battery-icon");
var snoozeButton = document.getElementById("button-snooze");
var okButton = document.getElementById("button-ok");
var noButton = document.getElementById("button-no");
// var tiles = reasonList.getElementsByClassName("tile");
var checkboxes = document.getElementsByClassName("checkbox-item");

var submitButton = document.getElementById("submit-button");
var notifText = document.getElementById("notif-textarea");
var stepCount = document.getElementById("step-count");


// global variables
let initialValue = today.adjusted.steps;
let timeCounter = 1;
let lastStepCount = 0;
let curInterval = null;
let nextInterval = null;
let miniSteps = 0;
let miniTimer = 1;
let timeSchedule = null;
let startHour = null;
let startMin = null;
let endHour = null;
let endMin = null;
let miniSession = false;
let isSessionOn = false;
let handle = null;
let alarm = null;
let snoozeAlarm = null;
let command_id = 0;
let isSnoozeSet = false;
let isNoDisturb = false;
let session_id_old = null;
let session_id_new = null;

// let options_short = { "timeout": 60000 };
// let options_long = { "timeout": 259200000 }


//prompt sent to the phone
var NOTIFICATION = "Ready?";
var NOTIF_NO_SNOOZE = "Ready!";
var MINIMESSAGE = "Great job!";
var CLOSE = "Close";

// command received from the phone
var SNOOZE = "Snooze";
var DO_NOT_DISTURB = "Do Not Disturb On";
var REMOVE_DO_NOT_DISTURB = "Do Not Disturb Off";

// responses to be logged
var OKAY = "Okay";
var NO = "No";
var OTHER = "Other";

// vibration patterns
var PING = "ping";
var RING = "ring";
var APPRAISAL = "Great job being active!";
var BATTERY_WARNING = "Battery low. Please charge your Fitbit.";


var LOOPINTERVAL_SHORT = 3000;   // we can change the looping interval arbitrarily
var ONEHOUR = 60;
var TWOHOURS = 120;
var ONEMINUTE = 60000;
var THRESHOLD = 50;
var MINITHRESHOLD = 30;
var MINITIMELIMIT = 15;
var SNOOZETIME = 900000;
var FIVE_MINUTES = 300000;

// var ONEHOUR = 60;   
// var TWOHOURS = 120;  
// var ONEMINUTE = 6000;   
// var THRESHOLD = 50;
// var MINITHRESHOLD = 30;  
// var MINITIMELIMIT = 15;   
// var SNOOZETIME = 60000; 
// var FIVE_MINUTES = 30000;

// for logging and debugging ///////////////////////////////////
let options_long = { "timeout": 25920000000 }     // remember to change it back later
// var screenColor = document.getElementById("background")   // remember to change this later after done with debug
// var total = document.getElementById("total")
// var retention = document.getElementById("retention")
// var num_total = 0
// var num_retention = 0
const debug = true

function checkRetention() {
    let queue = []
    try {
        queue = readFileSync("_asap_queue", "cbor")
        // Ensure that the queue is an array
        if (!Array.isArray(queue)) {
            queue = []
        }
    }
    // If a saved queue could not be loaded
    catch (error) {
        // Continue with an empty queue
        queue = []
    }
    num_retention = queue.length;
    console.log("Number of retention on disk: " + num_retention)
    retention.text = "retention: " + num_retention
}


function checkTotal() {
    console.log("Total number of records generated since start: " + num_total)
    total.text = "total: " + num_total
}


///////////////////////////////////////////////////////////////

function retrieveTimeScheduleAndNextInterval() {
    try {
        timeSchedule = readFileSync("time_schedule", "cbor")
        startHour = parseInt(timeSchedule.substr(0, 2));
        startMin = parseInt(timeSchedule.substr(2, 2));
        endHour = parseInt(timeSchedule.substr(4, 2));
        endMin = parseInt(timeSchedule.substr(6, 2));
        debug && console.log("Retrieved time schedule from disk: " + timeSchedule);
    } catch (error) {
        debug && console.log("Never saved any time schedule on disk yet!")
    }
    try {
        let json_data = readFileSync("next_interval","cbor")
        nextInterval = json_data.nextInterval;
        debug && console.log("Retrieved next interval from disk: " + nextInterval);
    } catch (error) {
        debug && console.log("Never saved any next interval on disk yet!")
    }
}

function writeTimeScheduleOnDisk() {
    writeFileSync("time_schedule", timeSchedule, "cbor")
}

function writeNextIntervalOnDisk() {
    let json_data = { nextInterval }
    writeFileSync("next_interval", json_data, "cbor")
}


// messaging.peerSocket.onerror = function (err) {
//     // Handle any errors
//     console.log("Connection error: " + err.code + " - " + err.message);
// }


// prevent the app from being killed by the system
me.appTimeoutEnabled = false;


// Suppress the system default action for the "back" physical button
document.onkeypress = function (e) {
    e.preventDefault();
    debug && console.log("Key pressed: " + e.key);
}


// constantly check: 1)if it's time to start the app; 2)the connection status
setInterval(function () {
    // console.log("now isNoDisturb is: " + isNoDisturb)
    // connStatus.text = messaging.peerSocket.readyState == messaging.peerSocket.OPEN ? "(Status: Connected)" : "(Status: Disconnected)";
    stepCount.text = today.adjusted.steps;
    if (!isSessionOn && nextInterval != null && checkTime()) {
        isSessionOn = true;
        isNoDisturb = false;  // reset DND mode to be off
        debug && console.log("The real session is on!");
        startNewSession();
        // screenColor.href = "cyan.png"
    }
    debug && console.log(messaging.peerSocket.readyState == messaging.peerSocket.OPEN ? "(Status: Connected)" : "(Status: Disconnected)");
    // debug && checkTotal()
    // debug && checkRetention()
}, LOOPINTERVAL_SHORT);


// Update the clock every minute
clock.granularity = "minutes";

clock.ontick = function () {
    updateClock();
}



// Update the battery status
battery.onchange = function () {
    let percentage = Math.floor(battery.chargeLevel);
    batteryLevel.text = percentage + "%";
    checkBattery(percentage);
}


// Update the current time
function updateClock() {
    let today = new Date();
    let hours = util.zeroPad(today.getHours());
    let mins = util.zeroPad(today.getMinutes());
    let isMorning = (hours / 12) == 0;
    if (((hours % 12) == 0) && (!isMorning)) timeLabel.text = `12:${mins}`;
    else timeLabel.text = `${hours % 12}:${mins}`;
}


// function to check if the current time is within the time schedule
function checkTime() {
    if (timeSchedule == null) return false;
    let today = new Date();
    let morningTime = new Date();
    let eveningTime = new Date();
    morningTime.setHours(startHour);
    morningTime.setMinutes(startMin);
    eveningTime.setHours(endHour);
    eveningTime.setMinutes(endMin);
    if (startHour > endHour) {
        if (today < eveningTime || today >= morningTime) return true;
    } else {
        if (today >= morningTime && today < eveningTime) return true;
    }
    return false;
}


function checkBattery(percentage) {
    if (charger.connected) {
        batteryIcon.href = "battery_charge.png";
        batteryIcon.style.fill = "yellow";
        return;
    }
    if (percentage > 90) {
        batteryIcon.href = "battery_full.png";
        batteryIcon.style.fill = "green";
    } else if (percentage >= 65 && percentage <= 90) {
        batteryIcon.href = "battery_75.png";
        batteryIcon.style.fill = "green";
    } else if (percentage > 40 && percentage < 65) {
        batteryIcon.href = "battery_half.png"
        batteryIcon.style.fill = "green";
    } else if (percentage > 10 && percentage <= 40) {
        batteryIcon.href = "battery_low.png";
        if (percentage <= 25) {
            batteryIcon.style.fill = "red";
            setFeedbackLayout(PING, BATTERY_WARNING);
        } else {
            batteryIcon.style.fill = "green";
        }
    } else {
        batteryIcon.href = "battery_empty.png";
        batteryIcon.style.fill = "red";
    }
}


// -----------------------------------------------a series of onclick event listeners-------------------------------------------------
okButton.onclick = function (evt) {
    debug && console.log("ok-button is clicked!");
    setMainLayout();
    vibration.stop();
    // TO DO: save the timestamp and response to database!!!
    let prompt = CLOSE;
    // asap.send({ prompt, "sessionId": session_id_old }, options_short);
    if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
        messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
    }
    let timeStamp = Date.now();
    let response = OKAY;
    asap.send({ timeStamp, "sessionId": session_id_old, response }, options_long);
    // num_total += 1
}

snoozeButton.onclick = function (evt) {
    debug && console.log("snooze-button is clicked!");
    setMainLayout();
    vibration.stop();
    isSnoozeSet = true;

    let prompt = CLOSE;
    // asap.send({ prompt, "sessionId": session_id_old }, options_short);
    if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
        messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
    }

    snoozeAlarm = setTimeout(function () {
        setMiniSession();

        let prompt = NOTIF_NO_SNOOZE;
        // asap.send({ prompt, "sessionId": session_id_old }, options_short);
        if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
            messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
        }

        setResponseLayout(false, RING);
        isSnoozeSet = false;
    }, SNOOZETIME);
    // TO DO: save the timestamp and response to database!!!
    let timeStamp = Date.now();
    let response = SNOOZE;
    asap.send({ timeStamp, "sessionId": session_id_old, response }, options_long);
    // num_total += 1

}

noButton.onclick = function (evt) {
    debug && console.log("no-button is clicked!");
    vibration.stop();
    showReasonList();

    let prompt = CLOSE;
    // asap.send({ prompt, "sessionId": session_id_old }, options_short);
    if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
        messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
    }

    let timeStamp = Date.now();
    let response = NO;
    asap.send({ timeStamp, "sessionId": session_id_old, response }, options_long);
    // num_total += 1

}

feedbackLayout.onclick = function (evt) {
    setMainLayout();
}


submitButton.onclick = function (evt) {
    setMainLayout();
    let reasons = "";
    checkboxes.forEach(function (element, index) {
        if (index == 4 && element.value == 1) {

            let prompt = OTHER;
            // asap.send({ prompt, "sessionId": session_id_old }, options_short);
            if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
                messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
            }

        }
        reasons += element.value;
        element.value = 0;
    });
    reasonList.value = 0;
    let timeStamp = Date.now();
    asap.send({ timeStamp, "sessionId": session_id_old, reasons }, options_long);
    // num_total += 1

}

// --------------------------------------------------functions to set layouts-----------------------------------------------------------------------


function setMainLayout() {
    mainLayout.style.visibility = "visible";
    responseLayout.style.visibility = "hidden";
    feedbackLayout.style.visibility = "hidden";
    reasonList.style.visibility = "hidden";
}


function setResponseLayout(snoozeOption, vibraPattern) {
    clearTimeout(alarm);
    if (checkTime()) {
        display.on = true; // turn on the screen
        snoozeButton.style.display = snoozeOption ? "inline" : "none";
        mainLayout.style.visibility = "hidden";
        responseLayout.style.visibility = "visible";
        feedbackLayout.style.visibility = "hidden";
        reasonList.style.visibility = "hidden";
        vibration.start(vibraPattern);
        alarm = setTimeout(setMainLayout, FIVE_MINUTES);
        let timeStamp = Date.now();
        let notif = snoozeOption ? 1 : 2;
        asap.send({ timeStamp, "sessionId": session_id_old, notif }, options_long);
        // num_total += 1

    }
}


function setFeedbackLayout(vibraPattern, message) {
    clearTimeout(alarm);
    clearTimeout(snoozeAlarm);
    if (checkTime()) {
        display.on = true;
        mainLayout.style.visibility = "hidden";
        responseLayout.style.visibility = "hidden";
        feedbackLayout.style.visibility = "visible";
        reasonList.style.visibility = "hidden";
        notifText.textContent = message;
        vibration.start(vibraPattern);
        alarm = setTimeout(setMainLayout, FIVE_MINUTES);
        let timeStamp = Date.now();

        let notif = message == APPRAISAL ? 0 : 3;
        asap.send({ timeStamp, "sessionId": session_id_old, notif }, options_long);
        // num_total += 1

    }
}

function showReasonList() {
    clearTimeout(alarm);
    mainLayout.style.visibility = "hidden";
    responseLayout.style.visibility = "hidden";
    feedbackLayout.style.visibility = "hidden";
    checkboxes.forEach(function (element, index) {
        element.value = 0;
    });
    reasonList.value = 0;
    reasonList.style.visibility = "visible";
    alarm = setTimeout(setMainLayout, ONEMINUTE);
}


// --------------------------------------------------------------------------------------------------------------------------------------


// Listen for the message from companion phone
messaging.peerSocket.onmessage = function (evt) {
    let message = evt.data;
    if (typeof message === "string") {
        debug && console.log("Watch received: " + message);
        if (message.trim() === ">=7".trim()) {
            debug && console.log("Next interval has been changed to two hours");
            nextInterval = TWOHOURS;
            writeNextIntervalOnDisk();
        } else if (message.trim() === "<7".trim()) {
            debug && console.log("Next interval has been changed to one hour");
            nextInterval = ONEHOUR;
            writeNextIntervalOnDisk();
        } else {
            timeSchedule = message;  // like "07002030",etc.
            startHour = parseInt(timeSchedule.substr(0, 2));
            startMin = parseInt(timeSchedule.substr(2, 2));
            endHour = parseInt(timeSchedule.substr(4, 2));
            endMin = parseInt(timeSchedule.substr(6, 2));
            writeTimeScheduleOnDisk();
        }
    }
}



asap.onmessage = message => {

    if (message.command_id > command_id) {
        command_id = message.command_id;
        debug && console.log("Now command_id is: " + command_id + " " + message.command);
        if (message.command.trim() == DO_NOT_DISTURB.trim()) {
            isNoDisturb = true;
            debug && console.log(isNoDisturb);
        } else if (message.command.trim() == REMOVE_DO_NOT_DISTURB.trim()) {
            isNoDisturb = false;
        } else {
            setMainLayout();
            clearTimeout(alarm);
            if (message.command.trim() == SNOOZE.trim()) {
                if (!isSnoozeSet) {
                    snoozeAlarm = setTimeout(function () {
                        setMiniSession();

                        let prompt = NOTIF_NO_SNOOZE;
                        // asap.send({ prompt, "sessionId": session_id_old }, options_short);
                        if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
                            messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
                        }

                        setResponseLayout(false, RING);
                    }, SNOOZETIME);
                }
            }
        }
    }

}


// ------------------------------------------------main algorithms--------------------------------------------------------------------

//function to set the mini-sessions
function setMiniSession() {
    debug && console.log("Mini-session starts!");
    miniSession = true;
    miniSteps = 0;
    miniTimer = 1;
}

// Function to start a new session
function startNewSession() {
    // generate a new uuid for the new session
    session_id_old = session_id_new;
    session_id_new = util.guid();
    // initialize the variables/counters
    debug && console.log("The new session starts!");
    timeCounter = 1;
    lastStepCount = 0;
    initialValue = today.adjusted.steps;
    curInterval = nextInterval;
    clearInterval(handle);
    handle = setInterval(collectData, ONEMINUTE);
    // collectData();

}

// function to log data and send it to phone
function collectData() {
    if (checkTime()) {
        let stepData = today.adjusted.steps - initialValue;
        let diff = stepData - lastStepCount;
        let timeStamp = Date.now();
        if (stepData < 0) {
            stepData = today.adjusted.steps;
            diff = today.adjusted.steps;
            initialValue = 0;
        }
        if (diff != 0) {
            let type = 2;
            let sensorData = diff;
            let data = { timeStamp, "sessionId": session_id_new, type, sensorData };
            if (checkTime()) {
                asap.send(JSON.parse(JSON.stringify(data)), options_long);
                // num_total += 1

            }

        }
        debug && console.log("Time is: " + timeCounter + ", step count is: " + stepData + ", diff is: " + diff);

        // check for mini-session
        if (miniSession) {
            miniSteps = miniSteps + diff;
            if (miniSteps >= MINITHRESHOLD) {
                miniSession = false;
                if (!isNoDisturb) {

                    let prompt = MINIMESSAGE;
                    // if (checkTime()) asap.send({ prompt, "sessionId": session_id_old }, options_short);
                    if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
                        messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
                    }

                }
                let type = 3;
                let sensorData = miniSteps;
                let data = { timeStamp, "sessionId": session_id_old, type, sensorData };
                if (checkTime()) {
                    asap.send(JSON.parse(JSON.stringify(data)), options_long);
                    // num_total += 1

                }
                if (!isNoDisturb) {
                    setFeedbackLayout(PING, APPRAISAL);
                }
            }
            if (miniTimer == MINITIMELIMIT && miniSession) {
                miniSession = false;
                let type = 3;
                let sensorData = miniSteps;
                let data = { timeStamp, "sessionId": session_id_old, type, sensorData };
                if (checkTime()) {
                    asap.send(JSON.parse(JSON.stringify(data)), options_long);
                    // num_total += 1

                }
            }
            miniTimer = miniTimer + 1;
        }
        // check if the threshold is reached already
        if (stepData >= THRESHOLD) {
            startNewSession();
            let type = curInterval == ONEHOUR ? 0 : 1;
            let sensorData = stepData;
            let data = { timeStamp, "sessionId": session_id_old, type, sensorData };
            if (checkTime()) {
                asap.send(JSON.parse(JSON.stringify(data)), options_long);
                // num_total += 1

            }
            setMainLayout();
            return;
        }
        // if the current interval ends...
        if (timeCounter === curInterval) {
            startNewSession();
            //send notification
            if (!isNoDisturb) {

                let prompt = NOTIFICATION;
                // asap.send({ prompt, "sessionId": session_id_old }, options_short);
                if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN) {
                    messaging.peerSocket.send({ prompt, "sessionId": session_id_old });
                }

            }
            let type = curInterval == ONEHOUR ? 0 : 1;
            let sensorData = stepData;
            let data = { timeStamp, "sessionId": session_id_old, type, sensorData };
            if (checkTime()) {
                asap.send(JSON.parse(JSON.stringify(data)), options_long);
                // num_total += 1
            }
            if (!isNoDisturb) {
                setResponseLayout(true, RING);
            }
            setMiniSession();
            return;
        }
        lastStepCount = stepData;
        timeCounter = timeCounter + 1;
    } else {
        isSessionOn = false;
        // screenColor.href = "black.jpeg"
        clearInterval(handle);
        debug && console.log("Sleeping mode...");
    }
}



// Initialize the app...
retrieveTimeScheduleAndNextInterval();
setMainLayout();
// hrm.start();
batteryLevel.text = Math.floor(battery.chargeLevel) + "%";
checkBattery(Math.floor(battery.chargeLevel));

