# Please provide basic issue information

Issues may be answered much faster, if you provide more details ahead!

In the last years, especially two kind of issues consumed a lot of our time, and it may be much faster, if you provide therefore this information **ahead**.

## Issue type 1:

Californium is used together with an other implementation. Please provide, where you run Californium (client or server side) and which other implementation you use. Even if the other implementation is a proprietary one, it is important to know, that it is not Californium. Without traces and captures in the very most cases we can not help you. So please really consider to provide that all ahead! 

## Issue type 2:

Network environment, e.g. k8s, docker, NATs, firewalls, virtual machines. All that may apply unaware changes to your traffic and may result in failures. In some cases it is also interesting to other users, if such issues occurs. So, still please report them. But the most of them can not be fixed in Californium, they must be fixed in that network environment. Therefore it is very important, that you list all the used components. In order to ensure, that it is no Californium issue, we kindly ask you to verify, that the issue doesn't occur, if a simple network environment ist used (e.g. running in two raspberry PIs).

## Information ahead to speed up the processing of issues:

* branch your faced the issue. Currently
    * (1.0 is the previous release, usually no bugfixes are applied)
    * (1.1 is the previous master, no plans to continue this)
    * 2.6.x is the current release,
    * and master (previous 2.0) is the development branch and our upcoming 3.0 release!
* If you use a SNAPSHOT, ensure your updated to the latest. Please provide the commit your using
* If your issue is related to a combination with other implementations, please mention that implementation (including proprietary ones). And the sides your using it, client or server.
* If your issue is related to your own snippet, please provide that
* If you have logs, please provide them
* If you have tcpdump captures (wireshark), please provide them.

(See https://github.com/eclipse/californium/wiki/Logs-and-IP-Capturing-%E2%80%90-How-To-Provide-The-Right-Information .)
