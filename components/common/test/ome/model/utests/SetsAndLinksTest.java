package ome.model.utests;

import org.testng.annotations.*;
import java.util.List;

import ome.conditions.ApiUsageException;
import ome.model.containers.Dataset;
import ome.model.containers.Project;
import ome.model.core.Image;
import ome.model.core.Pixels;

import junit.framework.TestCase;


public class SetsAndLinksTest extends TestCase
{

    Project p;
    Dataset d;
    Image i;
    Pixels pix;
    
  @Configuration(beforeTestMethod = true)
    protected void setUp() throws Exception
    {
        p = new Project();
        d = new Dataset();
        i = new Image();
        pix = new Pixels();
    }
    
  @Test
    public void test_linking() throws Exception
    {
        p.linkDataset( d );
        
        assertTrue( p.linkedDatasetList().size() == 1);
        assertTrue( p.linkedDatasetIterator().next().equals( d ));
        
    }
    
  @Test
    public void test_unlinking() throws Exception
    {
        p.linkDataset( d );
        p.unlinkDataset( d );
        assertTrue( p.linkedDatasetList().size() == 0 );

        p.linkDataset( d );
        p.clearDatasetLinks();
        assertTrue( p.linkedDatasetList().size() == 0 );
        
    }
    
  @Test
    public void test_retrieving() throws Exception
    {
        p.linkDataset( d );
        List l = p.eachLinkedDataset( null );
        assertTrue( l.size() == 1 );
        assertTrue( l.get(0).equals( d ));
    }
    
  @Test
    public void test_adding_a_placeholder() throws Exception
    {
        Project p = new Project();
        Dataset d = new Dataset( new Long(1L), false );
        
        p.linkDataset( d );
    }
  
  @Test( groups = "ticket:60" )
  public void test_cantLinkNullSet() throws Exception
  {
      p.putAt( Project.DATASETLINKS, null); // This is a workaround.
      try { 
          p.linkDataset( d );
          fail("Should not be allowed.");
      } catch (ApiUsageException api) {
          // ok.
      }
  
  }
  
  @Test( groups = "ticket:60" )
  public void test_butWeStillWantToUseUnloadeds() throws Exception
  {
      d.unload();
      p.linkDataset( d );
  }
  
  @Test( groups = "ticket:60" )
  public void test_andTheReverseToo() throws Exception
  {
      d.putAt( Dataset.PROJECTLINKS, null); // This is a workaround.
      try { 
          p.linkDataset( d );
          fail("Should not be allowed.");
      } catch (ApiUsageException api) {
          // ok.
      }
  }
  
  
}
