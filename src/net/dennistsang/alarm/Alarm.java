package net.dennistsang.alarm;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.prefs.Preferences;

import com.google.gdata.client.calendar.CalendarQuery;
import com.google.gdata.client.calendar.CalendarService;
import com.google.gdata.data.DateTime;
import com.google.gdata.data.calendar.CalendarEventEntry;
import com.google.gdata.data.calendar.CalendarEventFeed;
import com.google.gdata.util.ServiceException;

/**
 * Alarm Clock based on Google Calendar Events
 * 
 * @author Dennis Tsang
 */
public class Alarm
{
	// Java Preferences Keys
	public static final String PREFERENCES_NODE_NAME = "/net/dennistsang/alarm";
	public static final String PREFERENCES_USERNAME = "username";
	public static final String PREFERENCES_MAGIC_COOKIE = "magic-cookie";
	
	// Settings
	/**
	 * Number of minutes added to the first event of the day to get the alarm time
	 * Example: If first event is at 9:00am and the setting is -100, the alarm will
	 * be set to 7:20am (100 minutes before 9:00)
	 */
	public static final int ALARM_OFFSET_FROM_EVENT = -100;
	
	/**
	 * Alarm event name
	 */
	public static final String ALARM_EVENT_TITLE = "alarm";
	
	/**
	 * Main method
	 * @param args null
	 */
	public static void main(String[] args)
	{
		// Check if we are in test mode (tests audio player)
		if(args.length > 0 && args[0].equals("test"))
		{
			URL url = Alarm.class.getResource("alarmclock.wav");
			
			Thread alarmPlayer = new AePlayWave(url);
			alarmPlayer.start();
			
			try
			{
				Thread.sleep(10000);
			}
			catch(InterruptedException e)
			{
			}
			System.exit(0);
		}
		// Initialize preferences
		Preferences prefs = Preferences.userRoot().node(PREFERENCES_NODE_NAME);
		String username = prefs.get(PREFERENCES_USERNAME, "");
		String calendarMagicCookie = prefs.get(PREFERENCES_MAGIC_COOKIE, "");

		// Scanner to accept keyboard input for configuration
		Scanner s = new Scanner(System.in);

		// If there's a calendar configuration previously used, see if we should use it now
		boolean useSavedCalendar = false;
		if(!calendarMagicCookie.equals(""))
		{
			System.out.print("Use saved calendar? (y/n) ");
			String useSavedCalendarInput = s.nextLine();
			if(useSavedCalendarInput.equals("y"))
			{
				useSavedCalendar = true;
			}
		}

		// No saved calendar (or rejected) so ask for info
		if(!useSavedCalendar)
		{
			// Ask for cookie
			System.out.print("Enter Google username: ");
			username = s.nextLine();
			System.out.print("Enter Google Calendar magic cookie: ");
			calendarMagicCookie = s.nextLine();

			// Save to prefs
			prefs.put(PREFERENCES_USERNAME, username);
			prefs.put(PREFERENCES_MAGIC_COOKIE, calendarMagicCookie);
		}
		
		// Create alarm object and let it go!
		new Alarm(username, calendarMagicCookie);
	}
	
	private String username;
	private String calendarMagicCookie;
	private Timer timer = new Timer();
	private Timer alarmTimer = new Timer();
	private Date nextAlarmTime;
	
	/**
	 * Constructor
	 * @param username Google username
	 * @param calendarMagicCookie The magic cookie of the calendar
	 */
	public Alarm(String username, String calendarMagicCookie)
	{
		this.username = username;
		this.calendarMagicCookie = calendarMagicCookie;

		this.init();
	}
	
	/**
	 * Initialize the alarm timer tasks
	 */
	public void init()
	{
		// Schedule alarm to check Google Calendar for tomorrow's events every hour.
		timer.scheduleAtFixedRate(new AlarmCheck(), 0, 1000*60*60);
	}
	
	/**
	 * A timer task to check for updated alarm times.
	 */
	public class AlarmCheck extends TimerTask
	{
		@Override
		public void run()
		{
			System.out.println("Checking calendar at " + Calendar.getInstance().getTime());
			
			// Pick which date to check.  Assume that past noon we check tomorrow's alarm.
			Calendar checkDate = Calendar.getInstance();
			if(checkDate.get(Calendar.HOUR_OF_DAY) >= 12)
			{
				checkDate.add(Calendar.DAY_OF_MONTH, 1);
			}
			
			try
			{
				Date retrievedNextAlarmTime = getAlarmTimeForDate(checkDate);
				if(retrievedNextAlarmTime == null)
				{
					// Do nothing
				}
				else if(retrievedNextAlarmTime.before(Calendar.getInstance().getTime()))
				{
					System.out.println("Alarm time for today already passed.");
				}
				else if(!retrievedNextAlarmTime.equals(nextAlarmTime))
				{
					if(alarmTimer != null)
					{
						alarmTimer.cancel();
					}
					alarmTimer = new Timer();
					alarmTimer.schedule(new AlarmActivate(), retrievedNextAlarmTime);
					nextAlarmTime = retrievedNextAlarmTime;
					
					System.out.println("Alarm set to " + nextAlarmTime);
				}
				else
				{
					System.out.println("Alarm set to " + nextAlarmTime);
				}
			}
			catch(Exception e)
			{
				e.printStackTrace();
				System.out.println("An error occurred at " + Calendar.getInstance().getTime() + ": " + e);
			}
			
		}
	}
	
	/**
	 * The timer task run when the alarm time has been reached.
	 * @author dennis
	 *
	 */
	public class AlarmActivate extends TimerTask
	{

		@Override
		public void run()
		{
			URL url = Alarm.class.getResource("alarmclock.wav");
			
			Thread alarmPlayer = new AePlayWave(url);
			alarmPlayer.start();
			
			System.out.print("ALARM!!! Enter 'stop' to stop the alarm: ");
			Scanner s = new Scanner(System.in);
			while(!s.nextLine().equals("stop"))
			{
				System.out.println("WHAT?");
			}
			
			// Stop alarm
			alarmPlayer.interrupt();
			System.out.println("Alarm stopped!");
		}
		
	}

	/**
	 * Gets the alarm time for specified date
	 * Picks the first event of the day or an event titled "alarm" if it exists on that day.
	 * If first event is taken, adds an offset to get the alarm time.
	 * @param date Date to get alarm for
	 * @return The time of the alarm
	 * @throws Exception
	 */
	private Date getAlarmTimeForDate(Calendar date) throws Exception
	{
		System.out.println("Checking date " + date.getTime());
		
		CalendarEventFeed feed = getCalendarEventFeedForDate(date);
		
		if(feed.getTotalResults() == 0)
		{
			// No results
			System.out.println("No events.");
			return null;
		}

		// Get first event and first event titled "alarm"
		CalendarEventEntry firstEvent = null;
		CalendarEventEntry alarmEvent = null;
		for (CalendarEventEntry e : feed.getEntries())
		{
			if(firstEvent == null)
			{
				firstEvent = e;
			}
			if(e.getTitle().getPlainText().toLowerCase().equals(ALARM_EVENT_TITLE))
			{
				alarmEvent = e;
				break;
			}
		}

		// Choose valid event
		CalendarEventEntry useEvent;
		Date alarmDate;
		if(alarmEvent != null)
		{
			useEvent = alarmEvent;
			DateTime alarmDateTime = alarmEvent.getTimes().get(0).getStartTime();
			alarmDate = new Date(alarmDateTime.getValue());
		}
		else
		{
			useEvent = firstEvent;
			DateTime alarmDateTime = firstEvent.getTimes().get(0).getStartTime();
			Calendar cal = Calendar.getInstance();
			Date eventDate = new Date(alarmDateTime.getValue());
			cal.setTime(eventDate);
			cal.add(Calendar.MINUTE, ALARM_OFFSET_FROM_EVENT);
			alarmDate = cal.getTime();
		}

		
		System.out.println("Event: " + useEvent.getTitle().getPlainText() + " @ " + useEvent.getTimes().get(0).getStartTime());
		System.out.println("Alarm time calculated to: " + alarmDate);

		return alarmDate;
	}
	
	/**
	 * Retrieves list of events from Google Calendar for specified day
	 * @param date Day to retrieve list of events
	 * @return CalendarEventFeed of events on specified day
	 * @throws Exception
	 */
	private CalendarEventFeed getCalendarEventFeedForDate(Calendar date) throws Exception
	{
		// Set time to start searching for at 3am
		date.set(Calendar.HOUR_OF_DAY, 3);
		date.set(Calendar.MINUTE, 0);
		date.set(Calendar.SECOND, 0);
		// Calculate end date.
		Calendar endDate = (Calendar)date.clone();
		endDate.add(Calendar.DAY_OF_MONTH, 1);
		
		try
		{
			// Grab agenda for specified date
			
			URI feedUri = new URI("https://www.google.com/calendar/feeds/" + username + "/private-" + calendarMagicCookie + "/full");
			CalendarQuery myQuery = new CalendarQuery(feedUri.toURL());
			myQuery.setMinimumStartTime(new DateTime(date.getTime()));
			myQuery.setMaximumStartTime(new DateTime(endDate.getTime()));
			myQuery.setStringCustomParameter("orderby", "starttime");
			myQuery.setStringCustomParameter("sortorder", "ascending");
			myQuery.setStringCustomParameter("singleevents", "true");

			CalendarService myService = new CalendarService("dennistt-alarm-1");

			// Send the request and receive the response:
			CalendarEventFeed resultFeed = myService.query(myQuery, CalendarEventFeed.class);

			return resultFeed;
		}
		catch(URISyntaxException e1)
		{
			e1.printStackTrace();
			throw new Exception("Error with username or calendar magic cookie syntax.");
		}
		catch(MalformedURLException e1)
		{
			e1.printStackTrace();
			throw new Exception("Error with username or calendar magic cookie syntax.");
		}
		catch(IOException e1)
		{
			e1.printStackTrace();
			throw new Exception("Error connecting to Google Calendar.");
		}
		catch(ServiceException e1)
		{
			e1.printStackTrace();
			throw new Exception("Error retrieving calendar entries.");
		}
	}
}
