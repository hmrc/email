# Email  
 
# Overview

The Email microservice is responsible for sending emails on behalf of HMRC services. All email requests are treated as
transactional operations: they are first placed into a queue for orderly processing. The microservice ensures reliable
delivery by systematically processing each request from the queue. n the event that an email fails to send, the system 
automatically retries until its queued by email service provider.

## Features
- It is used by services that need to send emails.
- Track delivery status like bounces, opens, sent, etc.
- It a way for services to access event data like bounces, opens, sent etc.

## Useful links

-  [Email microservice runbook](https://confluence.tools.tax.service.gov.uk/display/~george.loizou/Email+microservice)
-  [Define email templates](https://github.com/hmrc/hmrc-email-renderer)
-  [Email event data](https://github.com/hmrc/event-hub)
-  [Digital Contact Slack channel - #team-digital-contact](https://hmrcdigital.slack.com/archives/C0J85LC3W)
-  [Metrics dashboard](https://grafana.tools.production.tax.service.gov.uk/d/dc-live-email/dc-live-email?orgId=1&from=now-24h&to=now&timezone=browser&refresh=15m)


### API Endpoint
The email microservice is available at the following endpoint:
```shell
 POST /:domain/email
```
Where `:domain` is the domain you want to send the email from, e.g. `hmrc` for some emails.

#### Payload for using this endpoint
```json
{
  "to": ["example@domain.com"],
  "templateId": "my-lovely-template",
  "parameters": {
    "name": "Mr Joe Bloggs"
  },
  "enrolment": "HMRC-CUS-ORG~EORINumber~GB123456789000",
  "force": false,
  "eventUrl": "http://some.other/url",
  "onSendUrl": "http://some/send/check/url",
  "tags": {
    "anyKey1": "anyValue",
    "anyKey2": "anyValue"
  }
}


```
where 
* `to` (mandatory) is the recipient email address
* `templateId` (mandatory) is the id of the template to be used to render the content.
* `parameters` is a map of key,values that is passed to the template to render the content.
* `enrolment` is an optional `~` (tilde) separated string containing an enrolment name, identifier and value
* if `force` is set to `true` the service will try to send the email regardless of emails in suppression list in imi
* `onSendUrl` is an optional callback for performing a [pre-send check](#pre-send-check) to verify that the email should still be sent.
* `eventUrl` is deprecated use event-hub instead.
* `tags` is an optional map of key,values that will be returned back along with the email response.

Response
```shell
Responds with 202 status if the request is valid and has been queued for sending. 
```

## Events

The email microservice can send events to the event-hub. These events can be used to track the status of the email messages after they have been sent.

We have to configure your service endpoint in `application.conf` to allow the events to be sent to your service. The endpoint must be a publicly accessible URL that can handle POST requests.

Possible events that your service may receive are:

| Name              | Description
| ----------------- | ------------
| `Sent`            | The message has been sent to the messaging provider
| `Delivered`       | The message has been accepted by and delivered to the ESP (ie. GMail, Hotmail etc.)
| `PermanentBounce` | The message was rejected by the ESP, or was not sent because messages to that address have previously been rejected by the ESP.
| `Opened`          | The message was viewed - or rather, a tracking image in the message was accessed



# Developer Information

## Integration Testing

Prior to running integration tests, ensure the profile `DC_EMAIL_IT` has been started with service manager.

## Run the project locally

```shell
sbt run "8300 -Dplay.http.router=testOnlyDoNotUseInAppConf.Routes"

```

## SBT tasks

```bash
# Format the code
sbt fmt

# Clean, build test and integration test
sbt clean test it/test

# Run a coverage report
sbt clean coverage test coverageReport
```

## FAQ

### How do I send an email from the @tax.service.gov.uk domain with the HMRC branding?

Good news, you are in the right place! You can use above API endpoint to send an email request.
All emails sent via this API have a standard HMRC branded header and footer.

### What is a template Id?
A templateId has to be provided instead of the classic subject and body you would expect when sending an email.
A template id is used to call an email renderer service and retrieve the email content.

Using an email renderer service rather than allowing services to provide the subject and the body has multiple benefits:
* Consistency of header and footer across all emails of the same domain
* Enforces HMRC policies around what may be sent in email
* The rendering logic is implemented in one single place


### How do I create a new template?

All HMRC templates are rendered in [hmrc-email-renderer](http://github.com/HMRC/hmrc-email-renderer).
If the email is for a new template, you will have to open a Pull Request on that repository.


### how do I send using non hmrc domains?

The good news is that it is now possible to send non-HMRC branded emails. 
Following domains are supported https://github.com/hmrc/email/blob/main/conf/application.conf#L104.

### Is my email sent out immediately?

##### Outbound Flow Management

We have to work hard to ensure that email service providers - GMail, Hotmail, Yahoo, etc. - do not perceive our email as spam. 
Sending reputation is tied to the addresses that we send from, and is shared by all users of same domain. 

One of the things that ESPs don't like is to have large spikes in sending rates of messages. 
To handle this, the service queues outgoing messages internally, and then throttles the rate at which messages are passed onto the email provider. 

###### How do I control different sending priorities?

Email service allows the users to send emails with different priorities: urgent, background and standard.
If you want to know more about the priorities usage when sending emails, please go to [`Volumes and priority recommendations`](https://github.com/hmrc/hmrc-email-renderer/blob/master/CONTRIBUTING.md#volumes-and-priority-recommendations) on hmrc-email-renderer.

The rate of sending varies by multiple factors, so do discuss with us if you have any specific requirements.

If you would like the email service to check back with you just before the email is sent out, 
i.e. when it reaches the front of the queue, then you can use the "onSendUrl" parameter when  to register a callback,.

##### Dead address protection

Another way that we protect the sending reputation is by blocking messages to address which the ESP has indicated are not valid. For each email address, if a bounced message occurs, the email service provider will drop messages all subsequent messages to it.

In some cases it is necessary to send a message regardless of whether a previous email to the same address has bounced. 
For example, the user indicates that they have rectified the problem with their email address and is explicitly asking for the email to be sent to the same address again. 
In this case the `force` flag can be used when sending the email. 
If true, before sending the email, we will inform the provider to ignore previous bounces. 
**Only use this feature when the user has explicitly indicated that their address should work**

### Where can I see if my emails are being sent?

The service generates extensive metrics that allow email deliverability to be monitored. 
These are collected in a [Grafana dashboard](https://grafana.tools.production.tax.service.gov.uk/d/dc-live-email/dc-live-email) that is viewable by all teams.


## AllowListing

In DEV and QA there is a allowList to prevent emails being sent to non `@digital.hmrc.gov.uk` addresses, this means emails will be accepted to the queue but will be deleted without being sent when processed.  If you need to change the allowList please contact Digital Contact team.

## Configuration can be used to block particular ISP domains when emails start bouncing

To be used only in cases where one or more ISPs start bouncing large number of emails.

This is to ***only*** be used temporarily until the ISP stops bouncing emails from HMRC. 

The configuration parameter is a regex with key ```senderDomains.<sender domain>.holdList``` and is used to block ISP recipient domains. 

Note: If the regex is malformed the email microservice will not start.

### Examples:

```
senderDomains.hmrc.holdList.0=btinternet.com|btinternet.co.uk|btopenworld.com
```

Note: This would only hold the specific recipient domains and all subdomains.

or 

```
senderDomains.hmrc.holdList.0=btinternet.*
senderDomains.hmrc.holdList.1=virgin.*
```

Note: Using a wildcard as shown above would cause ANY domain containing "btinternet" or "virgin" to be held until the configuration was removed.
