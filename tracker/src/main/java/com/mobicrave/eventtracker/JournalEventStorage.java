package com.mobicrave.eventtracker;

import com.google.common.collect.Lists;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.fusesource.hawtjournal.api.Journal;
import org.fusesource.hawtjournal.api.Location;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class JournalEventStorage implements EventStorage {
  private final Journal eventJournal;
  private final Journal metaDataJournal;
  private Event.MetaData[] metaDatas;
  private AtomicLong numEvents;

  private JournalEventStorage(Journal eventJournal, Journal metaDataJournal, Event.MetaData[] metaDatas,
      AtomicLong numEvents) {
    this.eventJournal = eventJournal;
    this.metaDataJournal = metaDataJournal;
    this.metaDatas = metaDatas;
    this.numEvents = numEvents;
  }

  @Override
  public long addEvent(Event event, long userId, int eventTypeId) {
    // TODO: 4B constraint
    int id = (int) numEvents.incrementAndGet();
    if (id >= metaDatas.length) {
      synchronized (this) {
        if (id >= metaDatas.length) {
          Event.MetaData[] newMetaDatas = new Event.MetaData[metaDatas.length * 2];
          System.arraycopy(metaDatas, 0, newMetaDatas, 0, metaDatas.length);
          metaDatas = newMetaDatas;
        }
      }
    }

    try {
      byte[] location = locationToBytes(eventJournal.write(event.toByteBuffer(), true));
      Event.MetaData metaData = event.getMetaData(userId, eventTypeId, location);
      metaDatas[id] = metaData;
      metaDataJournal.write(metaData.toByteBuffer(), true);
      return id;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Event.MetaData getEventMetaData(long eventId) {
    return metaDatas[(int) eventId];
  }

  @Override
  public Event getEvent(long eventId) {
    try {
      Location location = new Location();
      location.readExternal(ByteStreams.newDataInput(getEventMetaData(eventId).getLocation()));
      return Event.fromByteBuffer(eventJournal.read(location));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void close() {
    try {
      eventJournal.close();
      metaDataJournal.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private byte[] locationToBytes(Location location) throws IOException {
    ByteArrayDataOutput dos = ByteStreams.newDataOutput();
    location.writeExternal(dos);
    return dos.toByteArray();
  }

  public static JournalEventStorage build(String dataDir) {
    List<Event.MetaData> metaDatas = Lists.newArrayList();
    Journal eventJournal = createJournal(dataDir + "/event_journal/eventJournal/");
    Journal metaDataJournal = createJournal(dataDir + "/event_journal/metaDataJournal/");
    try {
      for (Location location : metaDataJournal) {
        metaDatas.add(Event.MetaData.fromByteBuffer(metaDataJournal.read(location)));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new JournalEventStorage(eventJournal, metaDataJournal,
        metaDatas.toArray(new Event.MetaData[1024]), new AtomicLong(-1));
  }

  private static Journal createJournal(String dirPath) {
    Journal journal = new Journal();
    File directory = new File(dirPath);
    directory.mkdirs();
    journal.setDirectory(directory);
    journal.setMaxFileLength(1024);
    journal.setMaxWriteBatchSize(1024);

    try {
      journal.open();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return journal;
  }
}
